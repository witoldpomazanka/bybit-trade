package com.mulaczos.blofin_trade.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mulaczos.blofin_trade.dto.AdvancedMarketPositionRequest;
import com.mulaczos.blofin_trade.exception.BlofinApiException;
import com.mulaczos.blofin_trade.model.TradeHistory;
import com.mulaczos.blofin_trade.repository.TradeHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class BlofinIntegrationService {

    private static final int DEFAULT_LEVERAGE = 10;

    @Value("${usd-amount-for-trade:10.0}")
    private Double usdAmountForTrade;

    private final BlofinApiClient blofinApiClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final TwilioNotificationService twilioNotificationService;
    private final LimitOrderService limitOrderService;
    private final TradeHistoryRepository tradeHistoryRepository;

    @EventListener(ApplicationStartedEvent.class)
    public void init() {
        log.info("Inicjalizacja BloFin - ustawianie domyślnej dźwigni {}x", DEFAULT_LEVERAGE);
        setLeverageForSymbol("BTCUSDT", DEFAULT_LEVERAGE);
        log.info("Wartość w USDT dla minimalnego domyślnego zagrania, jeśli nie zdefiniowane w payload: {}", usdAmountForTrade);
    }

    public JsonNode getOpenPositions() {
        log.info("Pobieranie otwartych pozycji z BloFin");
        JsonNode result = blofinApiClient.getPositions(false);
        log.info("Pobrano dane o otwartych pozycjach: {}", result);
        return result;
    }

    @Transactional
    public JsonNode openAdvancedPosition(Map<String, Object> payload, String chatTitle) {
        return openAdvancedPosition(AdvancedMarketPositionRequest.fromMap(payload), chatTitle);
    }

    @Transactional
    public JsonNode openAdvancedPosition(AdvancedMarketPositionRequest request, String chatTitle) {
        log.info("Wejście do openAdvancedPosition z request: {} i chatTitle: {}", request, chatTitle);
        log.info("Rozpoczynam otwieranie zaawansowanej pozycji: {}", request);
        try {
            String symbol = prepareAndValidateSymbol(request.getCoin());
            log.info("Przygotowany i zwalidowany symbol: {}", symbol);

            JsonNode validationCheck = validatePositionNotAlreadyOpen(symbol, chatTitle);
            if (validationCheck != null) {
                log.info("Walidacja nowej pozycji nie powiodła się: {}", validationCheck);
                return validationCheck;
            }

            setLeverageForSymbol(symbol, request.getLeverage());
            log.info("Ustawiono dźwignię {} dla {}", request.getLeverage(), symbol);

            BigDecimal initialMargin = BigDecimal.valueOf(usdAmountForTrade);
            BigDecimal totalPositionValue = initialMargin.multiply(BigDecimal.valueOf(request.getLeverage()));
            log.info("Obliczona wartość marży: {}, całkowita wartość pozycji: {}", initialMargin, totalPositionValue);

            if (request.isLimit()) {
                log.info("Kierowanie do handleLimitOrder");
                return handleLimitOrder(request, symbol, chatTitle, initialMargin, totalPositionValue);
            } else {
                log.info("Kierowanie do handleMarketOrder");
                return handleMarketOrder(request, symbol, chatTitle, initialMargin, totalPositionValue);
            }
        } catch (BlofinApiException ex) {
            log.error("Błąd API BloFin podczas otwierania pozycji [{}]: {}", ex.getApiCode(), ex.getApiMsg());
            return createErrorResponse("Błąd BloFin API [" + ex.getApiCode() + "]: " + ex.getApiMsg());
        } catch (IOException e) {
            log.error("Błąd podczas otwierania pozycji na BloFin: {}", e.getMessage(), e);
            throw new RuntimeException("Błąd podczas otwierania pozycji na BloFin: " + e.getMessage(), e);
        }
    }

    private JsonNode validatePositionNotAlreadyOpen(String symbol, String chatTitle) {
        log.info("validatePositionNotAlreadyOpen dla symbol: {}, chat: {}", symbol, chatTitle);
        if (chatTitle != null) {
            Optional<TradeHistory> existingTrade = tradeHistoryRepository.findFirstBySymbolAndChatTitleOrderByCreatedAtDesc(symbol, chatTitle);
            if (existingTrade.isPresent()) {
                log.warn("Znaleziono istniejącą pozycję dla symbolu {} z czatu {} - pomijam otwieranie nowej pozycji", symbol, chatTitle);
                return createErrorResponse("Dla symbolu " + symbol + " z czatu " + chatTitle + " istnieje już otwarta pozycja.");
            }
        }

        return checkIfThePositionForSymbolIsAlreadyOpened(symbol);
    }

    private JsonNode handleLimitOrder(AdvancedMarketPositionRequest request, String symbol, String chatTitle, BigDecimal initialMargin, BigDecimal totalPositionValue) throws IOException {
        log.info("handleLimitOrder START. InitialMargin: {}", initialMargin);
        log.info("Wykryto zlecenie limit - specjalna obsługa");
        log.info("Docelowa wartość pozycji (Total Position Value): {} USDT (Initial Margin: {} USDT x Leverage: {}x)",
                totalPositionValue, initialMargin, request.getLeverage());

        BigDecimal quantityInCrypto = calculatePositionSize(
                symbol,
                new BigDecimal(request.getEntryPrice()),
                totalPositionValue
        );
        log.info("handleLimitOrder - ilość w kontraktach: {}", quantityInCrypto.toPlainString());

        String orderType = "Limit";
        String orderPrice = request.getEntryPrice();
        BigDecimal finalValueUsdt = calculateApproximateValue(symbol, quantityInCrypto, new BigDecimal(orderPrice));
        log.info("handleLimitOrder - finalValueUsdt: {}", finalValueUsdt.toPlainString());

        log.info("Parametry zlecenia limit - symbol: {}, side: {}, cena: {}, qty: {}, stopLoss: {}, totalValue: {} USDT, finalValue: {} USDT",
                symbol, request.getSide(), orderPrice, quantityInCrypto.toPlainString(), request.getStopLoss(), totalPositionValue.toPlainString(), finalValueUsdt.toPlainString());

        JsonNode openResult = blofinApiClient.openPosition(
                symbol,
                request.getSide(),
                orderType,
                quantityInCrypto.toPlainString(),
                orderPrice,
                null,
                request.getStopLoss()
        );
        log.info("handleLimitOrder - Wynik z BlofinApiClient: {}", openResult);

        saveTradeHistory(symbol, request, quantityInCrypto.toPlainString(), chatTitle, orderType, orderPrice, finalValueUsdt.toPlainString());

        if (isSuccessfulOrder(openResult)) {
            String orderId = openResult.get("data").get(0).get("orderId").asText();
            log.info("handleLimitOrder - Zlecenie udane, zapisuję do tracker-a. OrderID: {}", orderId);
            limitOrderService.saveLimitOrder(orderId, request, symbol, quantityInCrypto.toPlainString());
        }

        sendSms(request, symbol, openResult, orderPrice, finalValueUsdt.toPlainString());
        return openResult;
    }

    private JsonNode handleMarketOrder(AdvancedMarketPositionRequest request, String symbol, String chatTitle, BigDecimal initialMargin, BigDecimal totalPositionValue) throws IOException {
        log.info("handleMarketOrder START. TotalPositionValue: {}", totalPositionValue);
        log.info("Rozpoczynam przygotowanie pozycji Market");
        log.info("Pobieranie aktualnej ceny rynkowej dla {}", symbol);
        double currentPrice = blofinApiClient.getMarketPrice("linear", symbol);
        BigDecimal price = BigDecimal.valueOf(currentPrice);
        log.info("handleMarketOrder - aktualna cena: {}", price);

        log.info("=== OBLICZANIE POZYCJI ===");
        log.info("Initial Margin (Twoja kwota wejścia z property): {} USDT", initialMargin);
        log.info("Leverage: {}x", request.getLeverage());
        log.info("Total Position Value (wartość pozycji na giełdzie): {} USDT", totalPositionValue);
        log.info("Cena entrada: {} USDT", price);

        BigDecimal quantityInCrypto = calculatePositionSize(symbol, price, totalPositionValue);
        log.info("handleMarketOrder - obliczone qty (kontrakty): {}", quantityInCrypto.toPlainString());
        
        BigDecimal approximateValue = calculateApproximateValue(symbol, quantityInCrypto, price);
        log.info("Obliczona wielkość pozycji: {} kontraktów (wartość: ~{} USDT)", quantityInCrypto.toPlainString(), approximateValue.toPlainString());

        String orderType = "Market";
        log.info("Parametry zlecenia - symbol: {}, side: {}, typ: {}, qty: {}, stopLoss: {}, dźwignia: {}x",
                symbol, request.getSide(), orderType, quantityInCrypto.toPlainString(), request.getStopLoss(), request.getLeverage());

        JsonNode openResult = blofinApiClient.openPosition(
                symbol,
                request.getSide(),
                orderType,
                quantityInCrypto.toPlainString(),
                null,
                null,
                request.getStopLoss()
        );
        log.info("handleMarketOrder - Wynik otwarcia: {}", openResult);

        String executedQty = extractExecutedQty(openResult, quantityInCrypto);
        String avgPrice = extractAvgPrice(openResult, price);
        BigDecimal executedValue = calculateApproximateValue(symbol, new BigDecimal(executedQty), new BigDecimal(avgPrice));
        String finalUsdtValue = executedValue.toPlainString();
        log.info("handleMarketOrder - ExecutedQty: {}, AvgPrice: {}, FinalUSDT: {}", executedQty, avgPrice, finalUsdtValue);

        saveTradeHistory(symbol, request, executedQty, chatTitle, "Market", avgPrice, finalUsdtValue);

        if (shouldConfigurePartialTakeProfits(request)) {
            log.info("Konfiguracja partial TP dla zlecenia Market");
            configurePartialTakeProfits(symbol, request);
        }

        log.info("Zakończono otwieranie pozycji z sukcesem");
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
        log.info("saveTradeHistory dla symbol: {}, qty: {}, usdt: {}", symbol, quantity, finalUsdtAmount);
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
        log.info("Zapisano historię zlecenia: {}", tradeHistory);
    }

    private void sendSms(AdvancedMarketPositionRequest request, String symbol, JsonNode openResult, String entryPrice, String finalUsdtAmount) {
        log.info("sendSms START. Symbol: {}", symbol);
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
        log.info("checkIfThePositionForSymbolIsAlreadyOpened dla {}", symbol);
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
                    log.warn("Znaleziono już otwartą pozycję dla symbolu: {}. Wielkość pozycji: {}",
                            symbol, posSize);
                    return createErrorResponse("Dla symbolu " + symbol + " istnieje już otwarta pozycja. " +
                            "Zamknij istniejącą pozycję przed otwarciem nowej.");
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
        log.info("Szukanie symbolu dla coina: {}", coin);
        String symbol = blofinApiClient.findCorrectSymbol(coin);
        if (!blofinApiClient.isSymbolSupported(symbol)) {
            throw new RuntimeException("Symbol " + symbol + " nie jest obsługiwany na BloFin");
        }
        log.info("Znaleziony i zwalidowany symbol: {}", symbol);
        return symbol;
    }

    private void setLeverageForSymbol(String symbol, int leverage) {
        log.info("setLeverageForSymbol: {} -> {}x", symbol, leverage);
        log.info("Ustawianie dźwigni {}x dla symbolu {}", leverage, symbol);
        blofinApiClient.setLeverage(symbol, String.valueOf(leverage));
    }

    private boolean shouldConfigurePartialTakeProfits(AdvancedMarketPositionRequest request) {
        boolean should = request.hasPartialTakeProfits() && request.getTakeProfit() == null;
        log.info("shouldConfigurePartialTakeProfits: {}", should);
        return should;
    }

    private void configurePartialTakeProfits(String symbol, AdvancedMarketPositionRequest request) throws IOException {
        log.info("configurePartialTakeProfits START dla {}", symbol);

        BigDecimal totalQty = getOpenedPositionQty(symbol);
        if (totalQty.compareTo(BigDecimal.ZERO) <= 0) {
            log.warn("Brak otwartej pozycji dla {}, pomijam konfigurację TP", symbol);
            return;
        }

        Map<Integer, String> partialTps = request.getPartialTakeProfits();
        int requestedTpCount = partialTps.size();

        BigDecimal minQty = getMinimumOrderQuantity(symbol);
        BigDecimal qtyStep = getQuantityStep(symbol);
        log.info("Wielkość pozycji: {} kontraktów. Minimalna ilość TP: {}, krok: {}", totalQty.toPlainString(), minQty.toPlainString(), qtyStep.toPlainString());

        // Sprawdzamy ile poziomów możemy obsłużyć (każdy musi mieć co najmniej minQty)
        int maxPossibleTps = totalQty.divide(minQty, 0, RoundingMode.DOWN).intValue();
        int actualTpCount = Math.min(requestedTpCount, maxPossibleTps);

        if (actualTpCount < requestedTpCount) {
             log.warn("Zmniejszono liczbę poziomów TP z {} do {} z powodu minSize={}", requestedTpCount, actualTpCount, minQty.toPlainString());
        }

        if (actualTpCount == 0) {
            log.error("Nie można ustawić ani jednego poziomu TP! Wielkość pozycji {} jest za mała dla minSize {}", totalQty.toPlainString(), minQty.toPlainString());
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

            log.info("Ustawianie TP#{} dla {}, cena: {}, rozmiar: {} kontraktów", (processedCount + 1), symbol, tp.getValue(), tpSize.toPlainString());

            Map<String, Object> tpReq = new HashMap<>();
            tpReq.put("category", "linear");
            tpReq.put("symbol", symbol);
            tpReq.put("tpslMode", "Partial");
            tpReq.put("tpOrderType", "Market");
            tpReq.put("tpSize", tpSize.toPlainString());
            tpReq.put("takeProfit", tp.getValue());
            tpReq.put("positionIdx", 0);

            if (request.getStopLoss() != null) {
                tpReq.put("stopLoss", request.getStopLoss());
            }

            callTradingStop(tpReq);

            remainingQty = remainingQty.subtract(tpSize);
            processedCount++;
            
            if (isLast) break;
        }
        log.info("Zakończono konfigurację partial take-profits");
    }

    public BigDecimal getMinOrderValue(String symbol) throws IOException {
        log.info("getMinOrderValue dla {}", symbol);
        JsonNode instrumentInfo = blofinApiClient.getInstrumentsInfo(symbol);
        if (instrumentInfo.has("data") && instrumentInfo.get("data").isArray()
                && !instrumentInfo.get("data").isEmpty()) {
            JsonNode instrument = instrumentInfo.get("data").get(0);
            
            BigDecimal minSize = new BigDecimal(instrument.get("minSize").asText());
            BigDecimal ctVal = BigDecimal.ONE;
            if (instrument.has("contractValue")) {
                ctVal = new BigDecimal(instrument.get("contractValue").asText());
            } else if (instrument.has("ctVal")) {
                ctVal = new BigDecimal(instrument.get("ctVal").asText());
            }
            
            // To jest minimalny wolumen w krypto (np BTC)
            BigDecimal minCryptoQty = minSize.multiply(ctVal);
            
            // Pobieramy cenę, aby obliczyć wartość w USDT
            double currentPrice = blofinApiClient.getMarketPrice("linear", symbol);
            return minCryptoQty.multiply(BigDecimal.valueOf(currentPrice)).setScale(2, RoundingMode.UP);
        }
        throw new IOException("Nie udało się pobrać minimalnej wartości zlecenia dla symbolu " + symbol);
    }

    public BigDecimal getContractValue(String symbol) throws IOException {
        JsonNode instrumentInfo = blofinApiClient.getInstrumentsInfo(symbol);
        if (instrumentInfo.has("data") && instrumentInfo.get("data").isArray()
                && !instrumentInfo.get("data").isEmpty()) {
            JsonNode instrument = instrumentInfo.get("data").get(0);
            if (instrument.has("contractValue")) {
                return new BigDecimal(instrument.get("contractValue").asText());
            } else if (instrument.has("ctVal")) {
                return new BigDecimal(instrument.get("ctVal").asText());
            }
        }
        return BigDecimal.ONE;
    }

    private BigDecimal calculateApproximateValue(String symbol, BigDecimal contracts, BigDecimal price) throws IOException {
        BigDecimal ctVal = getContractValue(symbol);
        return contracts.multiply(ctVal).multiply(price);
    }

    private BigDecimal calculatePositionSize(String symbol, BigDecimal price, BigDecimal positionValue) throws IOException {
        log.info("calculatePositionSize - price: {}, posValue: {}", price.toPlainString(), positionValue.toPlainString());

        BigDecimal ctVal = getContractValue(symbol);
        BigDecimal minQtyContracts = getMinimumOrderQuantity(symbol);
        BigDecimal qtyStepContracts = getQuantityStep(symbol);
        BigDecimal minOrderValueUsdt = getMinOrderValue(symbol);

        log.info("Parametry instrumentu {} - ctVal: {}, minSize: {}, lotSize: {}, minValueUsdt: {}", 
                symbol, ctVal.toPlainString(), minQtyContracts.toPlainString(), qtyStepContracts.toPlainString(), minOrderValueUsdt.toPlainString());

        // 1. Obliczamy ilość w bazowej walucie (np. BTC)
        BigDecimal quantityCrypto = positionValue.divide(price, 8, RoundingMode.UP);
        log.info("Wstępna ilość krypto (np. BTC): {}", quantityCrypto.toPlainString());

        // 2. Konwertujemy na liczbę kontraktów: contracts = quantityCrypto / ctVal
        BigDecimal quantityContracts = quantityCrypto.divide(ctVal, 8, RoundingMode.UP);
        log.info("Wstępna ilość kontraktów: {}", quantityContracts.toPlainString());

        // 3. Sprawdzamy minimalną wartość USDT
        if (positionValue.compareTo(minOrderValueUsdt) < 0) {
            log.warn("Wartość pozycji {} USDT poniżej minimum {} USDT - koryguję", positionValue.toPlainString(), minOrderValueUsdt.toPlainString());
            quantityContracts = minOrderValueUsdt.divide(price, 8, RoundingMode.UP).divide(ctVal, 8, RoundingMode.UP);
        }

        // 4. Sprawdzamy minimalną liczbę kontraktów
        if (quantityContracts.compareTo(minQtyContracts) < 0) {
            log.warn("Ilość kontraktów {} poniżej minimum {} - używamy minimum", quantityContracts.toPlainString(), minQtyContracts.toPlainString());
            quantityContracts = minQtyContracts;
        }

        // 5. Zaokrąglamy do lotSize
        quantityContracts = roundToValidQuantity(quantityContracts, qtyStepContracts, RoundingMode.UP);

        BigDecimal finalUsdtValue = quantityContracts.multiply(ctVal).multiply(price);
        log.info("calculatePositionSize FINAL - qty (contracts): {}, approx value: {} USDT", 
                quantityContracts.toPlainString(), finalUsdtValue.toPlainString());

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

    private void callTradingStop(Map<String, Object> tpReq) {
        log.info("callTradingStop z parametrami: {}", tpReq);
        try {
            blofinApiClient.setTradingStop(tpReq);
        } catch (Exception e) {
            throw new RuntimeException("Nie udało się ustawić częściowego TP/SL: " + tpReq, e);
        }
    }

    public BigDecimal getMinimumOrderQuantity(String symbol) throws IOException {
        log.info("getMinimumOrderQuantity dla {}", symbol);
        JsonNode instrumentInfo = blofinApiClient.getInstrumentsInfo(symbol);
        if (instrumentInfo.has("data") && instrumentInfo.get("data").isArray()
                && !instrumentInfo.get("data").isEmpty()) {
            JsonNode instrument = instrumentInfo.get("data").get(0);
            if (instrument.has("minSize")) {
                return new BigDecimal(instrument.get("minSize").asText());
            }
        }
        throw new IOException("Nie udało się pobrać minimalnej ilości zamówienia dla symbolu " + symbol);
    }

    public BigDecimal getQuantityStep(String symbol) throws IOException {
        log.info("getQuantityStep dla {}", symbol);
        JsonNode instrumentInfo = blofinApiClient.getInstrumentsInfo(symbol);
        if (instrumentInfo.has("data") && instrumentInfo.get("data").isArray()
                && !instrumentInfo.get("data").isEmpty()) {
            JsonNode instrument = instrumentInfo.get("data").get(0);
            if (instrument.has("lotSize")) {
                return new BigDecimal(instrument.get("lotSize").asText());
            }
        }
        throw new IOException("Nie udało się pobrać kroku ilości (lotSize) dla symbolu " + symbol);
    }

    public BigDecimal roundToValidQuantity(BigDecimal quantity, BigDecimal qtyStep) {
        return roundToValidQuantity(quantity, qtyStep, RoundingMode.UP);
    }

    public BigDecimal roundToValidQuantity(BigDecimal quantity, BigDecimal qtyStep, RoundingMode roundingMode) {
        log.info("roundToValidQuantity - qty: {}, step: {}, mode: {}", quantity.toPlainString(), qtyStep.toPlainString(), roundingMode);
        if (qtyStep.compareTo(BigDecimal.ZERO) == 0) {
            return quantity;
        }
        BigDecimal divided = quantity.divide(qtyStep, 0, roundingMode);
        BigDecimal result = divided.multiply(qtyStep);
        int scale = Math.max(0, qtyStep.scale());
        log.info("roundToValidQuantity RESULT: {}", result.toPlainString());
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

