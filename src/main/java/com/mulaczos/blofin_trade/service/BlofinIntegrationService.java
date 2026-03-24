package com.mulaczos.blofin_trade.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.mulaczos.blofin_trade.dto.AdvancedMarketPositionRequest;
import com.mulaczos.blofin_trade.dto.InstrumentInfo;
import com.mulaczos.blofin_trade.exception.BlofinApiException;
import com.mulaczos.blofin_trade.model.TradeHistory;
import com.mulaczos.blofin_trade.repository.TradeHistoryRepository;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class BlofinIntegrationService {

    @Value("${usd-amount-for-trade:20.0}")
    private Double usdAmountForTrade;

    private final BlofinApiClient blofinApiClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final LoadingCache<String, InstrumentInfo> instrumentCache;
    private final TwilioNotificationService twilioNotificationService;
    private final LimitOrderService limitOrderService;
    private final TradeHistoryRepository tradeHistoryRepository;

    public BlofinIntegrationService(BlofinApiClient blofinApiClient, TwilioNotificationService twilioNotificationService,
                                   LimitOrderService limitOrderService, TradeHistoryRepository tradeHistoryRepository) {
        this.blofinApiClient = blofinApiClient;
        this.twilioNotificationService = twilioNotificationService;
        this.limitOrderService = limitOrderService;
        this.tradeHistoryRepository = tradeHistoryRepository;
        
        // Caffeine cache z 1-minutowym TTL (Time To Live)
        this.instrumentCache = Caffeine.newBuilder()
                .expireAfterWrite(1, TimeUnit.MINUTES)
                .build(symbol -> {
                    log.debug("Cache miss dla symbolu: {}, pobieranie z API", symbol);
                    JsonNode instrumentInfo = blofinApiClient.getInstrumentsInfo(symbol);
                    if (instrumentInfo.has("data") && instrumentInfo.get("data").isArray()
                            && !instrumentInfo.get("data").isEmpty()) {
                        InstrumentInfo info = InstrumentInfo.fromJson(instrumentInfo.get("data").get(0));
                        log.debug("Zapamiętano informacje o instrumencie: {} (TTL: 1 min)", symbol);
                        return info;
                    }
                    throw new IOException("Nie udało się pobrać informacji o instrumencie: " + symbol);
                });
    }

    public JsonNode getOpenPositions() {
        JsonNode result = blofinApiClient.getPositions(false);
        log.info("Pobrano otwarte pozycje (raw): {}", result);
        return result;
    }

    @Transactional
    public JsonNode openAdvancedPosition(Map<String, Object> payload, String chatTitle) {
        return openAdvancedPosition(AdvancedMarketPositionRequest.fromMap(payload), chatTitle);
    }

    @Transactional
    public JsonNode openAdvancedPosition(AdvancedMarketPositionRequest request, String chatTitle) {
        log.info("Otwieranie pozycji: symbol={}, side={}, leverage={}x, type={}, Chat={}",
                request.getCoin(), request.getSide(), request.getLeverage(), request.isLimit() ? "LIMIT" : "MARKET", chatTitle);
        try {
            String symbol = prepareAndValidateSymbol(request.getCoin());

            JsonNode validationCheck = validatePositionNotAlreadyOpen(symbol, chatTitle);
            if (validationCheck != null) {
                log.info("Walidacja negatywna - pozycja już istnieje dla: {}", symbol);
                return validationCheck;
            }

            setLeverageForSymbol(symbol, request.getLeverage());

            BigDecimal initialMargin = BigDecimal.valueOf(usdAmountForTrade);
            BigDecimal totalPositionValue = initialMargin.multiply(BigDecimal.valueOf(request.getLeverage()));

            if (request.isLimit()) {
                return handleLimitOrder(request, symbol, chatTitle, initialMargin, totalPositionValue);
            } else {
                return handleMarketOrder(request, symbol, chatTitle, initialMargin, totalPositionValue);
            }
        } catch (BlofinApiException ex) {
            log.error("Błąd API BloFin [{}]: {}", ex.getApiCode(), ex.getApiMsg());
            return createErrorResponse("Błąd BloFin API [" + ex.getApiCode() + "]: " + ex.getApiMsg());
        } catch (IOException e) {
            log.error("Błąd IO przy otwieraniu pozycji: {}", e.getMessage());
            throw new RuntimeException("Błąd IO przy otwieraniu pozycji: " + e.getMessage(), e);
        }
    }

    private JsonNode validatePositionNotAlreadyOpen(String symbol, String chatTitle) {
        JsonNode apiCheck = checkIfThePositionForSymbolIsAlreadyOpened(symbol);

        if (chatTitle != null) {
            Optional<TradeHistory> existingTrade = tradeHistoryRepository.findFirstBySymbolAndChatTitleOrderByCreatedAtDesc(symbol, chatTitle);
            if (existingTrade.isPresent()) {
                TradeHistory trade = existingTrade.get();
                if (apiCheck != null) {
                    log.warn("Blokada: API potwierdziło otwartą pozycję dla {} z czatu {}", symbol, chatTitle);
                    return createErrorResponse("Dla symbolu " + symbol + " z czatu " + chatTitle + " istnieje już otwarta pozycja.");
                } else {
                    log.info("Poprzedni wpis w DB dla {} (czat: {}) dotyczy zamkniętej pozycji. Kontynuuję.", symbol, chatTitle);
                }
            } else if (apiCheck != null) {
                log.warn("Blokada: API wykryło otwartą pozycję dla {} (brak wpisu w DB)", symbol);
                return apiCheck;
            }
        } else if (apiCheck != null) {
            log.warn("Blokada: API wykryło otwartą pozycję dla {} (chatTitle=null)", symbol);
            return apiCheck;
        }

        return null;
    }

    private JsonNode handleLimitOrder(AdvancedMarketPositionRequest request, String symbol, String chatTitle, BigDecimal initialMargin, BigDecimal totalPositionValue) throws IOException {
        log.info("Przetwarzanie zlecenia LIMIT: symbol={}, price={}, margin={} USDT", symbol, request.getEntryPrice(), initialMargin);

        // Pobierz cenę rynkową
        double currentMarketPrice = blofinApiClient.getMarketPrice("linear", symbol);
        log.info("Aktualna cena rynkowa {}: ${}", symbol, String.format("%.2f", currentMarketPrice));

        BigDecimal entryPriceBD = new BigDecimal(request.getEntryPrice());
        BigDecimal marketPriceBD = BigDecimal.valueOf(currentMarketPrice);
        
        // Sprawdź czy cena jest już po stronie zlecenia
        boolean priceAlreadyFavorable = false;
        if ("Buy".equalsIgnoreCase(request.getSide())) {
            // LONG: jeśli cena rynkowa > entry price, to już jest poniżej entry
            priceAlreadyFavorable = marketPriceBD.compareTo(entryPriceBD) > 0;
            if (priceAlreadyFavorable) {
                log.info("⚠ LONG zlecenie: cena rynkowa (${}) > entry (${}) - zmiana na MARKET!", 
                    String.format("%.2f", currentMarketPrice), request.getEntryPrice());
            }
        } else if ("Sell".equalsIgnoreCase(request.getSide())) {
            // SHORT: jeśli cena rynkowa < entry price, to już jest powyżej entry
            priceAlreadyFavorable = marketPriceBD.compareTo(entryPriceBD) < 0;
            if (priceAlreadyFavorable) {
                log.info("⚠ SHORT zlecenie: cena rynkowa (${}) < entry (${}) - zmiana na MARKET!", 
                    String.format("%.2f", currentMarketPrice), request.getEntryPrice());
            }
        }

        // Jeśli cena jest już po stronie - wyślij MARKET zamiast LIMIT
        if (priceAlreadyFavorable) {
            return handleMarketOrder(request, symbol, chatTitle, initialMargin, totalPositionValue);
        }

        // Jeśli nie - kontynuuj z LIMIT
        BigDecimal quantityInCrypto = calculatePositionSize(
                symbol,
                entryPriceBD,
                totalPositionValue
        );

        String orderType = "Limit";
        String orderPrice = request.getEntryPrice();
        BigDecimal finalValueUsdt = calculateApproximateValue(symbol, quantityInCrypto, entryPriceBD);

        log.info("Parametry zlecenia LIMIT: qty={}, SL={}, approxValue={} USDT", quantityInCrypto.toPlainString(), request.getStopLoss(), finalValueUsdt.toPlainString());

        JsonNode openResult = blofinApiClient.openPosition(
                symbol,
                request.getSide(),
                orderType,
                quantityInCrypto.toPlainString(),
                orderPrice,
                null,
                request.getStopLoss()
        );

        saveTradeHistory(symbol, request, quantityInCrypto.toPlainString(), chatTitle, orderType, orderPrice, finalValueUsdt.toPlainString());

        if (isSuccessfulOrder(openResult)) {
            String orderId = openResult.get("data").get(0).get("orderId").asText();
            log.info("Zlecenie LIMIT udane: orderId={}", orderId);
            limitOrderService.saveLimitOrder(orderId, request, symbol, quantityInCrypto.toPlainString());
        } else {
            log.warn("Zlecenie LIMIT zwróciło nietypowy wynik: {}", openResult);
        }

        return openResult;
    }

    private JsonNode handleMarketOrder(AdvancedMarketPositionRequest request, String symbol, String chatTitle, BigDecimal initialMargin, BigDecimal totalPositionValue) throws IOException {
        log.info("Przetwarzanie zlecenia MARKET: symbol={}, margin={} USDT", symbol, initialMargin);
        double currentPrice = blofinApiClient.getMarketPrice("linear", symbol);
        BigDecimal price = BigDecimal.valueOf(currentPrice);

        BigDecimal quantityInCrypto = calculatePositionSize(symbol, price, totalPositionValue);
        BigDecimal approximateValue = calculateApproximateValue(symbol, quantityInCrypto, price);

        log.info("Parametry zlecenia MARKET: qty={}, price={}, SL={}, approxValue={} USDT",
                quantityInCrypto.toPlainString(), price.toPlainString(), request.getStopLoss(), approximateValue.toPlainString());

        JsonNode openResult = blofinApiClient.openPosition(
                symbol,
                request.getSide(),
                "Market",
                quantityInCrypto.toPlainString(),
                null,
                null,
                request.getStopLoss()
        );

        String executedQty = extractExecutedQty(openResult, quantityInCrypto);
        String avgPrice = extractAvgPrice(openResult, price);
        BigDecimal executedValue = calculateApproximateValue(symbol, new BigDecimal(executedQty), new BigDecimal(avgPrice));
        String finalUsdtValue = executedValue.toPlainString();

        saveTradeHistory(symbol, request, executedQty, chatTitle, "Market", avgPrice, finalUsdtValue);

        if (shouldConfigurePartialTakeProfits(request)) {
            configurePartialTakeProfits(symbol, request);
        }

        log.info("Zlecenie MARKET wykonane: execQty={}, avgPrice={}, totalValue={} USDT", executedQty, avgPrice, finalUsdtValue);
        sendSms(request, symbol, openResult, avgPrice, finalUsdtValue);

        return openResult;
    }

    private boolean isSuccessfulOrder(JsonNode openResult) {
        boolean success = openResult.has("code") && "0".equals(openResult.get("code").asText())
                && openResult.has("data") && openResult.get("data").isArray()
                && !openResult.get("data").isEmpty()
                && openResult.get("data").get(0).has("orderId");
        log.info("isSuccessfulOrder: {}", success);
        return success;
    }

    private String extractExecutedQty(JsonNode openResult, BigDecimal fallback) {
        return openResult.has("data") && openResult.get("data").isArray()
                && !openResult.get("data").isEmpty()
                && openResult.get("data").get(0).has("qty")
                ? openResult.get("data").get(0).get("qty").asText()
                : fallback.toPlainString();
    }

    private String extractAvgPrice(JsonNode openResult, BigDecimal fallback) {
        return openResult.has("data") && openResult.get("data").isArray()
                && !openResult.get("data").isEmpty()
                && openResult.get("data").get(0).has("avgPrice")
                ? openResult.get("data").get(0).get("avgPrice").asText()
                : fallback.toPlainString();
    }

    private void saveTradeHistory(String symbol, AdvancedMarketPositionRequest request, String quantity, String chatTitle, String orderType, String entryPrice, String finalUsdtAmount) {
        TradeHistory tradeHistory = TradeHistory.builder()
                .symbol(symbol)
                .side(request.getSide())
                .quantity(quantity)
                .entryPrice(entryPrice)
                .stopLoss(request.getStopLoss())
                .takeProfit(request.getTakeProfit())
                .leverage(request.getLeverage())
                .chatTitle(chatTitle)
                .createdAt(LocalDateTime.now())
                .orderType(orderType)
                .usdtAmount(finalUsdtAmount)
                .build();

        tradeHistoryRepository.save(tradeHistory);
        log.info("Zapisano do historii DB: ID={}, symbol={}", tradeHistory.getId(), symbol);
    }

    private void sendSms(AdvancedMarketPositionRequest request, String symbol, JsonNode openResult, String entryPrice, String finalUsdtAmount) {
        String qty = "nieznana";
        if (openResult.has("data") && openResult.get("data").isArray()
                && !openResult.get("data").isEmpty()) {
            JsonNode firstOrder = openResult.get("data").get(0);
            if (firstOrder.has("qty")) {
                qty = firstOrder.get("qty").asText();
            } else if (firstOrder.has("size")) {
                qty = firstOrder.get("size").asText();
            }
        }

        twilioNotificationService.sendPositionOpenedNotification(
                symbol,
                request.getSide(),
                qty,
                request.getLeverage(),
                request.isLimit() ? "Limit" : "Market",
                entryPrice,
                finalUsdtAmount
        );
    }

    @Nullable
    private JsonNode checkIfThePositionForSymbolIsAlreadyOpened(String symbol) {
        JsonNode openPositions = getOpenPositions();
        if (openPositions.has("data") && openPositions.get("data").isArray()) {
            JsonNode positionsList = openPositions.get("data");
            String instId = symbol.contains("-") ? symbol : toInstId(symbol);
            for (JsonNode position : positionsList) {
                String posInstId = position.has("instId") ? position.get("instId").asText() : "";
                double posSize = position.has("positions")
                        ? Math.abs(position.get("positions").asDouble()) : 0;
                if ((posInstId.equals(instId) || posInstId.replace("-", "").equals(symbol))
                        && posSize > 0) {
                    log.warn("API: Pozycja dla {} jest już otwarta (size={})", symbol, posSize);
                    return createErrorResponse("Dla symbolu " + symbol + " istnieje już otwarta pozycja.");
                }
            }
        }
        return null;
    }

    private String toInstId(String symbol) {
        if (symbol == null || symbol.contains("-")) return symbol;
        if (symbol.endsWith("USDT")) {
            return symbol.substring(0, symbol.length() - 4) + "-USDT";
        }
        return symbol;
    }

    private String prepareAndValidateSymbol(String coin) throws IOException {
        String symbol = blofinApiClient.findCorrectSymbol(coin);
        if (!blofinApiClient.isSymbolSupported(symbol)) {
            throw new RuntimeException("Symbol " + symbol + " nie jest obsługiwany na BloFin");
        }
        return symbol;
    }

    private void setLeverageForSymbol(String symbol, int leverage) {
        try {
            blofinApiClient.setLeverage(symbol, String.valueOf(leverage));
            log.info("Ustawiono dźwignię: {}x dla {}", leverage, symbol);
        } catch (BlofinApiException e) {
            if (BlofinApiException.PENDING_ORDERS_PREVENT_LEVERAGE_ADJUSTMENT.equals(e.getApiCode())) {
                log.warn("Pominięto ustawianie dźwigni {} dla {}: aktywne zlecenia", leverage, symbol);
            } else {
                throw e;
            }
        }
    }

    private InstrumentInfo getCachedInstrumentInfo(String symbol) throws IOException {
        try {
            return instrumentCache.get(symbol);
        } catch (Exception e) {
            log.error("Błąd pobierania informacji o instrumencie z cache dla: {}", symbol, e);
            throw new IOException("Nie udało się pobrać informacji o instrumencie: " + symbol, e);
        }
    }

    private boolean shouldConfigurePartialTakeProfits(AdvancedMarketPositionRequest request) {
        boolean should = request.hasPartialTakeProfits() && request.getTakeProfit() == null;
        log.info("shouldConfigurePartialTakeProfits: {}", should);
        return should;
    }

    private void configurePartialTakeProfits(String symbol, AdvancedMarketPositionRequest request) throws IOException {
        BigDecimal totalQty = getOpenedPositionQty(symbol);
        if (totalQty.compareTo(BigDecimal.ZERO) <= 0) {
            log.warn("Pominięto Partial TP dla {}: brak otwartej pozycji", symbol);
            return;
        }

        Map<Integer, String> partialTps = request.getPartialTakeProfits();
        int requestedTpCount = partialTps.size();

        BigDecimal minQty = getMinimumOrderQuantity(symbol);
        BigDecimal qtyStep = getQuantityStep(symbol);

        int maxPossibleTps = totalQty.divide(minQty, 0, RoundingMode.DOWN).intValue();
        int actualTpCount = Math.min(requestedTpCount, maxPossibleTps);

        if (actualTpCount < requestedTpCount) {
            log.warn("Zmniejszono liczbę TP dla {} z {} do {} (minSize={})", symbol, requestedTpCount, actualTpCount, minQty.toPlainString());
        }

        if (actualTpCount == 0) {
            log.error("Nie można ustawić Partial TP dla {}: zbyt mała pozycja ({})", symbol, totalQty.toPlainString());
            return;
        }

        BigDecimal remainingQty = totalQty;
        int processedCount = 0;

        // Sortowanie poziomów TP po numerze (TreeMap już to zapewnia)
        for (Map.Entry<Integer, String> tp : partialTps.entrySet()) {
            if (processedCount >= actualTpCount) break;

            BigDecimal tpSize;
            boolean isLast = (processedCount == actualTpCount - 1);

            if (isLast) {
                tpSize = remainingQty;
            } else {
                // Proporcjonalny podział
                BigDecimal idealPart = totalQty.divide(BigDecimal.valueOf(actualTpCount), 8, RoundingMode.DOWN);
                tpSize = roundToValidQuantity(idealPart, qtyStep, RoundingMode.DOWN);

                // Walidacja czy nie schodzimy poniżej minSize dla tego lub przyszłych kroków
                if (tpSize.compareTo(minQty) < 0) tpSize = minQty;
                if (remainingQty.subtract(tpSize).compareTo(minQty) < 0) {
                    // Jeśli po zabraniu tego kawałka zostanie mniej niż minSize, musimy wziąć wszystko teraz lub skorygować
                    tpSize = remainingQty;
                    isLast = true;
                }
            }

            log.info("Ustawianie TP#{} dla {}: cena={}, size={}", (processedCount + 1), symbol, tp.getValue(), tpSize.toPlainString());

            String side = request.getSide().equalsIgnoreCase("Buy") ? "Sell" : "Buy";

            Map<String, Object> tpReq = new HashMap<>();
            tpReq.put("category", "linear");
            tpReq.put("symbol", symbol);
            tpReq.put("tpslMode", "Partial");
            tpReq.put("tpOrderType", "Market");
            tpReq.put("tpSize", tpSize.toPlainString());
            tpReq.put("takeProfit", tp.getValue());
            tpReq.put("positionIdx", 0);
            tpReq.put("side", side);

            if (request.getStopLoss() != null) {
                tpReq.put("stopLoss", request.getStopLoss());
            }

            callTradingStop(tpReq);

            remainingQty = remainingQty.subtract(tpSize);
            processedCount++;

            if (isLast) break;
        }
        log.info("Zakończono konfigurację Take-Profits dla {}", symbol);
    }

    public BigDecimal getMinOrderValue(String symbol) throws IOException {
        InstrumentInfo info = getCachedInstrumentInfo(symbol);
        BigDecimal minCryptoQty = info.getMinSize().multiply(info.getContractValue());
        double currentPrice = blofinApiClient.getMarketPrice("linear", symbol);
        return minCryptoQty.multiply(BigDecimal.valueOf(currentPrice)).setScale(2, RoundingMode.UP);
    }

    public BigDecimal getContractValue(String symbol) throws IOException {
        return getCachedInstrumentInfo(symbol).getContractValue();
    }

    private BigDecimal calculateApproximateValue(String symbol, BigDecimal contracts, BigDecimal price) throws IOException {
        BigDecimal ctVal = getContractValue(symbol);
        return contracts.multiply(ctVal).multiply(price);
    }

    private BigDecimal calculatePositionSize(String symbol, BigDecimal price, BigDecimal positionValue) throws IOException {
        BigDecimal ctVal = getContractValue(symbol);
        BigDecimal minQtyContracts = getMinimumOrderQuantity(symbol);
        BigDecimal qtyStepContracts = getQuantityStep(symbol);
        BigDecimal minOrderValueUsdt = getMinOrderValue(symbol);

        BigDecimal quantityCrypto = positionValue.divide(price, 8, RoundingMode.UP);
        BigDecimal quantityContracts = quantityCrypto.divide(ctVal, 8, RoundingMode.UP);

        if (positionValue.compareTo(minOrderValueUsdt) < 0) {
            quantityContracts = minOrderValueUsdt.divide(price, 8, RoundingMode.UP).divide(ctVal, 8, RoundingMode.UP);
        }

        if (quantityContracts.compareTo(minQtyContracts) < 0) {
            quantityContracts = minQtyContracts;
        }

        quantityContracts = roundToValidQuantity(quantityContracts, qtyStepContracts, RoundingMode.UP);

        BigDecimal finalUsdtValue = quantityContracts.multiply(ctVal).multiply(price);
        log.info("Obliczanie pozycji {}: qty={} (kontrakty), approxValue={} USDT", symbol, quantityContracts.toPlainString(), finalUsdtValue.toPlainString());

        return quantityContracts;
    }

    private BigDecimal getOpenedPositionQty(String symbol) {
        log.info("getOpenedPositionQty dla {}", symbol);
        try {
            JsonNode positions = blofinApiClient.getPositions(false);
            if (positions.has("data") && positions.get("data").isArray()) {
                String instId = toInstId(symbol);
                for (JsonNode pos : positions.get("data")) {
                    String posInstId = pos.has("instId") ? pos.get("instId").asText() : "";
                    if (posInstId.equals(instId) || posInstId.replace("-", "").equals(symbol)) {
                        if (pos.has("availablePositions")) {
                            return new BigDecimal(pos.get("availablePositions").asText()).abs();
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Błąd podczas pobierania ilości kontraktów dla symbolu: {}, błąd: {}",
                    symbol, e.getMessage());
            return BigDecimal.ZERO;
        }
        log.warn("Nie znaleziono otwartej pozycji dla symbolu: {}", symbol);
        return BigDecimal.ZERO;
    }

    public void callTradingStop(Map<String, Object> tpReq) {
        log.info("callTradingStop z parametrami: {}", tpReq);
        try {
            blofinApiClient.setTradingStop(tpReq);
        } catch (Exception e) {
            throw new RuntimeException("Nie udało się ustawić częściowego TP/SL: " + tpReq, e);
        }
    }

    public BigDecimal getMinimumOrderQuantity(String symbol) throws IOException {
        return getCachedInstrumentInfo(symbol).getMinSize();
    }

    public BigDecimal getQuantityStep(String symbol) throws IOException {
        return getCachedInstrumentInfo(symbol).getLotSize();
    }

    public BigDecimal roundToValidQuantity(BigDecimal quantity, BigDecimal qtyStep) {
        return roundToValidQuantity(quantity, qtyStep, RoundingMode.UP);
    }

    public BigDecimal roundToValidQuantity(BigDecimal quantity, BigDecimal qtyStep, RoundingMode roundingMode) {
        if (qtyStep.compareTo(BigDecimal.ZERO) == 0) {
            return quantity;
        }
        BigDecimal divided = quantity.divide(qtyStep, 0, roundingMode);
        BigDecimal result = divided.multiply(qtyStep);
        int scale = Math.max(0, qtyStep.scale());
        return result.setScale(scale, roundingMode);
    }

    public String extractBaseCoinFromSymbol(String symbol) {
        String withoutUSDT = symbol.endsWith("USDT") ? symbol.substring(0, symbol.length() - 4) : symbol;
        if (!withoutUSDT.isEmpty()) {
            StringBuilder coinName = new StringBuilder();
            boolean foundLetter = false;
            for (char c : withoutUSDT.toCharArray()) {
                if (Character.isLetter(c)) {
                    coinName.append(c);
                    foundLetter = true;
                } else if (foundLetter) {
                    coinName.append(c);
                }
            }
            if (!coinName.isEmpty()) {
                return coinName.toString();
            }
        }
        return withoutUSDT;
    }

    private JsonNode createErrorResponse(String errorMessage) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("BŁĄD", errorMessage);
        return response;
    }
}

