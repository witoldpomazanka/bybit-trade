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
        log.debug("DEBUG: Wejście do openAdvancedPosition z request: {} i chatTitle: {}", request, chatTitle);
        log.info("Rozpoczynam otwieranie zaawansowanej pozycji: {}", request);
        try {
            String symbol = prepareAndValidateSymbol(request.getCoin());
            log.debug("DEBUG: Przygotowany i zwalidowany symbol: {}", symbol);

            JsonNode validationCheck = validatePositionNotAlreadyOpen(symbol, chatTitle);
            if (validationCheck != null) {
                log.debug("DEBUG: Walidacja nowej pozycji nie powiodła się: {}", validationCheck);
                return validationCheck;
            }

            setLeverageForSymbol(symbol, request.getLeverage());
            log.debug("DEBUG: Ustawiono dźwignię {} dla {}", request.getLeverage(), symbol);

            BigDecimal initialMargin = BigDecimal.valueOf(usdAmountForTrade);
            BigDecimal totalPositionValue = initialMargin.multiply(BigDecimal.valueOf(request.getLeverage()));
            log.debug("DEBUG: Obliczona wartość marży: {}, całkowita wartość pozycji: {}", initialMargin, totalPositionValue);

            if (request.isLimit()) {
                log.debug("DEBUG: Kierowanie do handleLimitOrder");
                return handleLimitOrder(request, symbol, chatTitle, initialMargin, totalPositionValue);
            } else {
                log.debug("DEBUG: Kierowanie do handleMarketOrder");
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
        log.debug("DEBUG: validatePositionNotAlreadyOpen dla symbol: {}, chat: {}", symbol, chatTitle);
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
        log.debug("DEBUG: handleLimitOrder START. InitialMargin: {}", initialMargin);
        log.info("Wykryto zlecenie limit - specjalna obsługa");
        log.info("Docelowa wartość pozycji (Total Position Value): {} USDT (Initial Margin: {} USDT x Leverage: {}x)",
                totalPositionValue, initialMargin, request.getLeverage());

        BigDecimal quantityInCrypto = calculatePositionSize(
                symbol,
                BigDecimal.valueOf(Double.parseDouble(request.getEntryPrice())),
                totalPositionValue
        );
        log.debug("DEBUG: handleLimitOrder - ilość w krypto: {}", quantityInCrypto);

        String orderType = "Limit";
        String orderPrice = request.getEntryPrice();
        BigDecimal finalValueUsdt = quantityInCrypto.multiply(new BigDecimal(orderPrice));
        log.debug("DEBUG: handleLimitOrder - finalValueUsdt: {}", finalValueUsdt);

        log.info("Parametry zlecenia limit - symbol: {}, side: {}, cena: {}, qty: {}, stopLoss: {}, totalValue: {} USDT, finalValue: {} USDT",
                symbol, request.getSide(), orderPrice, quantityInCrypto, request.getStopLoss(), totalPositionValue, finalValueUsdt);

        JsonNode openResult = blofinApiClient.openPosition(
                symbol,
                request.getSide(),
                orderType,
                quantityInCrypto.toString(),
                orderPrice,
                null,
                request.getStopLoss()
        );
        log.debug("DEBUG: handleLimitOrder - Wynik z BlofinApiClient: {}", openResult);

        saveTradeHistory(symbol, request, quantityInCrypto.toString(), chatTitle, orderType, orderPrice, finalValueUsdt.toString());

        if (isSuccessfulOrder(openResult)) {
            String orderId = openResult.get("data").get(0).get("orderId").asText();
            log.debug("DEBUG: handleLimitOrder - Zlecenie udane, zapisuję do tracker-a. OrderID: {}", orderId);
            limitOrderService.saveLimitOrder(orderId, request, symbol, quantityInCrypto.toString());
        }

        sendSms(request, symbol, openResult, orderPrice, finalValueUsdt.toString());
        return openResult;
    }

    private JsonNode handleMarketOrder(AdvancedMarketPositionRequest request, String symbol, String chatTitle, BigDecimal initialMargin, BigDecimal totalPositionValue) throws IOException {
        log.debug("DEBUG: handleMarketOrder START. TotalPositionValue: {}", totalPositionValue);
        log.info("Rozpoczynam przygotowanie pozycji Market");
        log.info("Pobieranie aktualnej ceny rynkowej dla {}", symbol);
        double currentPrice = blofinApiClient.getMarketPrice("linear", symbol);
        BigDecimal price = BigDecimal.valueOf(currentPrice);
        log.debug("DEBUG: handleMarketOrder - aktualna cena: {}", price);

        log.info("=== OBLICZANIE POZYCJI ===");
        log.info("Initial Margin (Twoja kwota wejścia z property): {} USDT", initialMargin);
        log.info("Leverage: {}x", request.getLeverage());
        log.info("Total Position Value (wartość pozycji na giełdzie): {} USDT", totalPositionValue);
        log.info("Cena entrada: {} USDT", price);

        BigDecimal quantityInCrypto = calculatePositionSize(symbol, price, totalPositionValue);
        log.debug("DEBUG: handleMarketOrder - obliczone qty: {}", quantityInCrypto);
        log.info("Obliczona wielkość pozycji: {} (wartość: {} USDT)", quantityInCrypto, quantityInCrypto.multiply(price));

        String orderType = "Market";
        log.info("Parametry zlecenia - symbol: {}, side: {}, typ: {}, qty: {}, stopLoss: {}, dźwignia: {}x",
                symbol, request.getSide(), orderType, quantityInCrypto, request.getStopLoss(), request.getLeverage());

        JsonNode openResult = blofinApiClient.openPosition(
                symbol,
                request.getSide(),
                orderType,
                quantityInCrypto.toString(),
                null,
                null,
                request.getStopLoss()
        );
        log.debug("DEBUG: handleMarketOrder - Wynik otwarcia: {}", openResult);

        String executedQty = extractExecutedQty(openResult, quantityInCrypto);
        String avgPrice = extractAvgPrice(openResult, price);
        String finalUsdtValue = new BigDecimal(avgPrice).multiply(new BigDecimal(executedQty)).toString();
        log.debug("DEBUG: handleMarketOrder - ExecutedQty: {}, AvgPrice: {}, FinalUSDT: {}", executedQty, avgPrice, finalUsdtValue);

        saveTradeHistory(symbol, request, executedQty, chatTitle, "Market", avgPrice, finalUsdtValue);

        if (shouldConfigurePartialTakeProfits(request)) {
            log.debug("DEBUG: Konfiguracja partial TP dla zlecenia Market");
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
        log.debug("DEBUG: isSuccessfulOrder: {}", success);
        return success;
    }

    private String extractExecutedQty(JsonNode openResult, BigDecimal fallback) {
        return openResult.has("data") && openResult.get("data").isArray()
                && !openResult.get("data").isEmpty()
                && openResult.get("data").get(0).has("qty")
                ? openResult.get("data").get(0).get("qty").asText()
                : fallback.toString();
    }

    private String extractAvgPrice(JsonNode openResult, BigDecimal fallback) {
        return openResult.has("data") && openResult.get("data").isArray()
                && !openResult.get("data").isEmpty()
                && openResult.get("data").get(0).has("avgPrice")
                ? openResult.get("data").get(0).get("avgPrice").asText()
                : fallback.toString();
    }

    private void saveTradeHistory(String symbol, AdvancedMarketPositionRequest request, String quantity, String chatTitle, String orderType, String entryPrice, String finalUsdtAmount) {
        log.debug("DEBUG: saveTradeHistory dla symbol: {}, qty: {}, usdt: {}", symbol, quantity, finalUsdtAmount);
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
        log.debug("DEBUG: sendSms START. Symbol: {}", symbol);
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
        log.debug("DEBUG: checkIfThePositionForSymbolIsAlreadyOpened dla {}", symbol);
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
        log.debug("DEBUG: setLeverageForSymbol: {} -> {}x", symbol, leverage);
        log.info("Ustawianie dźwigni {}x dla symbolu {}", leverage, symbol);
        blofinApiClient.setLeverage(symbol, String.valueOf(leverage));
    }

    private boolean shouldConfigurePartialTakeProfits(AdvancedMarketPositionRequest request) {
        boolean should = request.hasPartialTakeProfits() && request.getTakeProfit() == null;
        log.debug("DEBUG: shouldConfigurePartialTakeProfits: {}", should);
        return should;
    }

    private void configurePartialTakeProfits(String symbol, AdvancedMarketPositionRequest request) throws IOException {
        log.debug("DEBUG: configurePartialTakeProfits START dla {}", symbol);

        double totalQty = getOpenedPositionQty(symbol);
        Map<Integer, String> partialTps = request.getPartialTakeProfits();
        int tpCount = partialTps.size();

        String baseCoin = extractBaseCoinFromSymbol(symbol);
        BigDecimal minQty = getMinimumOrderQuantity(symbol);
        log.info("Minimalny limit dla {}: {}", baseCoin, minQty);

        double basePartSize = Math.floor((totalQty / tpCount) * 100) / 100.0;
        double remainingQty = totalQty;

        for (Map.Entry<Integer, String> tp : partialTps.entrySet()) {
            configureSinglePartialTakeProfit(
                    symbol,
                    tp.getKey(),
                    tp.getValue(),
                    tpCount,
                    basePartSize,
                    remainingQty,
                    minQty,
                    request.getStopLoss()
            );

            if (tp.getKey() != tpCount) {
                remainingQty -= basePartSize;
            }
        }
        log.info("Zakończono konfigurację wszystkich partial take-profits");
    }

    private void configureSinglePartialTakeProfit(
            String symbol,
            int tpNumber,
            String takeProfitPrice,
            int totalTpCount,
            double basePartSize,
            double remainingQty,
            BigDecimal minQty,
            String stopLoss) {
        double tpSize;
        if (tpNumber == totalTpCount) {
            tpSize = remainingQty - (Math.floor(remainingQty / totalTpCount) * (totalTpCount - 1));
        } else {
            tpSize = basePartSize;
        }

        if (BigDecimal.valueOf(tpSize).compareTo(minQty) < 0) {
            tpSize = minQty.doubleValue();
        }

        log.debug("DEBUG: TP#{} dla {}, cena: {}, rozmiar: {}", tpNumber, symbol, takeProfitPrice, tpSize);

        Map<String, Object> tpReq = new HashMap<>();
        tpReq.put("category", "linear");
        tpReq.put("symbol", symbol);
        tpReq.put("tpslMode", "Partial");
        tpReq.put("tpOrderType", "Market");
        tpReq.put("tpSize", String.valueOf(tpSize));
        tpReq.put("takeProfit", takeProfitPrice);
        tpReq.put("positionIdx", 0);

        if (stopLoss != null) {
            tpReq.put("stopLoss", stopLoss);
        }

        callTradingStop(tpReq);
    }

    public BigDecimal getMinOrderValue(String symbol) throws IOException {
        log.debug("DEBUG: getMinOrderValue dla {}", symbol);
        JsonNode instrumentInfo = blofinApiClient.getInstrumentsInfo(symbol);
        if (instrumentInfo.has("data") && instrumentInfo.get("data").isArray()
                && !instrumentInfo.get("data").isEmpty()) {
            JsonNode instrument = instrumentInfo.get("data").get(0);
            if (instrument.has("minSize") && instrument.has("tickSize")) {
                BigDecimal minSize = new BigDecimal(instrument.get("minSize").asText());
                BigDecimal tickSize = new BigDecimal(instrument.get("tickSize").asText());
                return minSize.multiply(tickSize);
            }
        }
        throw new IOException("Nie udało się pobrać minimalnej wartości zlecenia dla symbolu " + symbol);
    }

    private BigDecimal calculatePositionSize(String symbol, BigDecimal price, BigDecimal positionValue) throws IOException {
        log.debug("DEBUG: calculatePositionSize - price: {}, posValue: {}", price, positionValue);

        BigDecimal minQtyFromApi = getMinimumOrderQuantity(symbol);
        BigDecimal minOrderValue = getMinOrderValue(symbol);

        // Qty = Position Value / Price
        BigDecimal quantity = positionValue.divide(price, 8, RoundingMode.HALF_UP);
        BigDecimal orderValue = quantity.multiply(price);

        log.info("Wstępna ilość: {}, wartość zamówienia: {} USDT", quantity, orderValue);
        log.info("Minimalne wymogi - ilość: {}, wartość: {} USDT", minQtyFromApi, minOrderValue);

        // Jeśli obliczona wartość zamówienia jest poniżej minimum, użyjemy wartości minimalnej
        if (orderValue.compareTo(minOrderValue) < 0) {
            log.warn("Wartość zamówienia {} USDT jest poniżej minimum {} USDT - dostosowanie ilości",
                    orderValue, minOrderValue);
            quantity = minOrderValue.divide(price, 8, RoundingMode.UP);
        }

        if (quantity.compareTo(minQtyFromApi) < 0) {
            log.warn("Ilość {} jest poniżej minimum {} - używamy minimum", quantity, minQtyFromApi);
            quantity = minQtyFromApi;
        }

        BigDecimal qtyStep = getQuantityStep(symbol);
        quantity = roundToValidQuantity(quantity, qtyStep);

        BigDecimal finalOrderValue = quantity.multiply(price);
        log.debug("DEBUG: calculatePositionSize FINAL - qty: {}, value: {}", quantity, finalOrderValue);
        log.info("Finalna ilość: {} (wartość: {} USDT)", quantity, finalOrderValue);

        return quantity;
    }

    private double getOpenedPositionQty(String symbol) {
        log.debug("DEBUG: getOpenedPositionQty dla {}", symbol);
        try {
            JsonNode positions = blofinApiClient.getPositions(false);
            if (positions.has("data") && positions.get("data").isArray()) {
                String instId = toInstId(symbol);
                for (JsonNode pos : positions.get("data")) {
                    String posInstId = pos.has("instId") ? pos.get("instId").asText() : "";
                    if (posInstId.equals(instId) || posInstId.replace("-", "").equals(symbol)) {
                        if (pos.has("positions")) {
                            return Math.abs(pos.get("positions").asDouble());
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Błąd podczas pobierania ilości kontraktów dla symbolu: {}, błąd: {}",
                    symbol, e.getMessage());
            return 0;
        }
        log.warn("Nie znaleziono otwartej pozycji dla symbolu: {}", symbol);
        return 0;
    }

    private void callTradingStop(Map<String, Object> tpReq) {
        log.debug("DEBUG: callTradingStop z parametrami: {}", tpReq);
        try {
            blofinApiClient.setTradingStop(tpReq);
        } catch (Exception e) {
            throw new RuntimeException("Nie udało się ustawić częściowego TP/SL: " + tpReq, e);
        }
    }

    public BigDecimal getMinimumOrderQuantity(String symbol) throws IOException {
        log.debug("DEBUG: getMinimumOrderQuantity dla {}", symbol);
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
        log.debug("DEBUG: getQuantityStep dla {}", symbol);
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
        log.debug("DEBUG: roundToValidQuantity - qty: {}, step: {}", quantity, qtyStep);
        if (qtyStep.compareTo(BigDecimal.ZERO) == 0) {
            return quantity;
        }
        BigDecimal divided = quantity.divide(qtyStep, 0, RoundingMode.UP);
        BigDecimal result = divided.multiply(qtyStep);
        int scale = Math.max(0, qtyStep.scale());
        log.debug("DEBUG: roundToValidQuantity RESULT: {}", result);
        return result.setScale(scale, RoundingMode.UP);
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

