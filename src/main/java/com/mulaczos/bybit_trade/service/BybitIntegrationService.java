package com.mulaczos.bybit_trade.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.mulaczos.bybit_trade.dto.AdvancedMarketPositionRequest;
import com.mulaczos.bybit_trade.dto.ScalpRequestDto;
import com.mulaczos.bybit_trade.dto.TradingResponseDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class BybitIntegrationService {

    private static final int DEFAULT_LEVERAGE = 10;
    private static final double DEFAULT_TP = 0.05;
    private static final Map<String, BigDecimal> HARD_MIN_QTY_LIMITS = new HashMap<>();
    private static int CURRENT_LEVERAGE;
    @Value("${min-usdt-amount-for-trade}")
    private Double minUsdtAmountForTrade;

    static {
        HARD_MIN_QTY_LIMITS.put("BTC", new BigDecimal("0.001")); // Minimalny limit dla BTC to 0.001
        HARD_MIN_QTY_LIMITS.put("ETH", new BigDecimal("0.01"));  // Minimalny limit dla ETH to 0.01
        HARD_MIN_QTY_LIMITS.put("SOL", new BigDecimal("0.1"));   // Minimalny limit dla SOL to 0.1
        HARD_MIN_QTY_LIMITS.put("DEFAULT", new BigDecimal("0.01"));
    }

    private final BybitApiClient bybitApiClient;

    @EventListener(ApplicationStartedEvent.class)
    public void init() {
        log.info("Inicjalizacja - ustawianie domyślnej dźwigni {}x", DEFAULT_LEVERAGE);
        setLeverageForSymbol("BTCUSDT", DEFAULT_LEVERAGE);
        log.info("Wartość w USDT dla minimalnego domyślnego zagrania, jeśli nie zdefiniowane w payload: {}", minUsdtAmountForTrade);
    }

    public JsonNode getOpenPositions() {
        log.info("Pobieranie otwartych pozycji z Bybit");
        JsonNode result = bybitApiClient.getPositions("linear", "USDT");
        log.info("Pobrano dane o otwartych pozycjach: {}", result);
        return result;
    }

    public JsonNode getAccountBalance() {
        log.info("Pobieranie salda konta z Bybit");
        JsonNode result = bybitApiClient.getWalletBalance("UNIFIED");
        log.info("Pobrano dane o saldzie konta: {}", result);
        return result;
    }

    public JsonNode openAdvancedMarketPosition(Map<String, Object> payload) {
        log.info("Rozpoczynam otwieranie zaawansowanej pozycji market z payload: {}", payload);
        return openAdvancedMarketPosition(AdvancedMarketPositionRequest.fromMap(payload));
    }

    public JsonNode openAdvancedMarketPosition(AdvancedMarketPositionRequest request) {
        log.info("Rozpoczynam otwieranie zaawansowanej pozycji market: {}", request);

        try {
            // 1. Przygotowanie i walidacja symbolu
            String symbol = prepareAndValidateSymbol(request.getCoin());

            // 2. Ustawienie dźwigni
            setLeverageForSymbol(symbol, request.getLeverage());

            // 3. Przygotowanie i otwarcie głównej pozycji
            JsonNode openResult = openMainPosition(symbol, request);

            // 4. Konfiguracja partial take-profits (jeśli są)
            if (shouldConfigurePartialTakeProfits(request)) {
                configurePartialTakeProfits(symbol, request);
            }

            log.info("Zakończono otwieranie pozycji z sukcesem");
            return openResult;
        } catch (IOException e) {
            log.error("Błąd podczas otwierania pozycji na Bybit: {}", e.getMessage(), e);
            throw new RuntimeException("Błąd podczas otwierania pozycji na Bybit: " + e.getMessage(), e);
        }
    }

    private String prepareAndValidateSymbol(String coin) throws IOException {
        log.info("Szukanie symbolu dla coina: {}", coin);
        String symbol = bybitApiClient.findCorrectSymbol(coin);
        if (!bybitApiClient.isSymbolSupported("linear", symbol)) {
            throw new RuntimeException("Symbol " + symbol + " nie jest obsługiwany w kategorii linear na Bybit");
        }
        log.info("Znaleziony i zwalidowany symbol: {}", symbol);
        return symbol;
    }

    private void setLeverageForSymbol(String symbol, int leverage) {
        if (leverage != CURRENT_LEVERAGE) {
            CURRENT_LEVERAGE = leverage;
            bybitApiClient.setLeverage("linear", symbol, String.valueOf(leverage));
            log.info("Ustawiano dźwignię {}x", CURRENT_LEVERAGE);
        } else {
            log.info("Obecna dźwignia jest taka sama jak proponowana, nie wysłano requestu o zmianę.");
        }
    }

    private JsonNode openMainPosition(String symbol, AdvancedMarketPositionRequest request) throws IOException {
        // Przygotowanie parametrów zlecenia
        double currentPrice = bybitApiClient.getMarketPrice("linear", symbol);
        BigDecimal price = BigDecimal.valueOf(currentPrice);
        log.info("Aktualna cena rynkowa dla {}: {}", symbol, price);

        // Obliczanie wielkości pozycji
        BigDecimal positionValueInUsdtAfterLeverageMultiply = request.getUsdtAmount() != null ? request.getUsdtAmount().multiply(BigDecimal.valueOf(request.getLeverage())) :
                BigDecimal.valueOf(minUsdtAmountForTrade).multiply(BigDecimal.valueOf(request.getLeverage()));

        BigDecimal quantityInCrypto = calculatePositionSize(symbol, price, positionValueInUsdtAfterLeverageMultiply);

        log.info("Obliczona wielkość pozycji: {}", quantityInCrypto);

        // Otwarcie pozycji
        log.info("Otwieranie głównej pozycji - symbol: {}, side: {}, qty: {}, takeProfit: {}, stopLoss: {}", symbol, request.getSide(), quantityInCrypto, request.getTakeProfit(), request.getStopLoss());

        return bybitApiClient.openPosition(
                "linear",
                symbol,
                request.getSide(),
                "Market",
                quantityInCrypto.toString(),
                null,
                request.getTakeProfit(),
                request.getStopLoss()
        );
    }

    private boolean shouldConfigurePartialTakeProfits(AdvancedMarketPositionRequest request) {
        return request.hasPartialTakeProfits() && request.getTakeProfit() == null;
    }

    private void configurePartialTakeProfits(String symbol, AdvancedMarketPositionRequest request) throws IOException {
        double totalQty = getOpenedPositionQty(symbol, "linear");
        Map<Integer, String> partialTps = request.getPartialTakeProfits();
        int tpCount = partialTps.size();

        log.info("Konfiguracja partial TP - całkowita ilość: {}, liczba TP: {}", totalQty, tpCount);

        // Pobierz minimalny limit dla danej kryptowaluty
        String baseCoin = extractBaseCoinFromSymbol(symbol);
        BigDecimal minQty = HARD_MIN_QTY_LIMITS.getOrDefault(baseCoin, HARD_MIN_QTY_LIMITS.get("DEFAULT"));
        log.info("Minimalny limit dla {}: {}", baseCoin, minQty);

        // Oblicz równe części dla wszystkich TP oprócz ostatniego
        double basePartSize = Math.floor((totalQty / tpCount) * 100) / 100.0;
        double remainingQty = totalQty;
        log.info("Bazowa wielkość dla każdego TP (oprócz ostatniego): {}", basePartSize);

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
    }

    private void configureSinglePartialTakeProfit(
            String symbol,
            int tpNumber,
            String takeProfitPrice,
            int totalTpCount,
            double basePartSize,
            double remainingQty,
            BigDecimal minQty,
            String stopLoss) throws IOException {

        double tpSize;
        if (tpNumber == totalTpCount) {
            // Dla ostatniego TP użyj dokładnie tej samej formuły co w starej wersji
            tpSize = remainingQty - (Math.floor(remainingQty / totalTpCount) * (totalTpCount - 1));
            log.info("Ostatni TP ({}), użycie pozostałej ilości: {}", tpNumber, tpSize);
        } else {
            tpSize = basePartSize;
            log.info("TP {}, użycie bazowej wielkości: {}, pozostało: {}", tpNumber, tpSize, remainingQty - basePartSize);
        }

        if (BigDecimal.valueOf(tpSize).compareTo(minQty) < 0) {
            tpSize = minQty.doubleValue();
            log.info("Wielkość TP skorygowana do minimalnego limitu: {}", tpSize);
        }

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

        log.info("Ustawianie partial TP {} - parametry: {}", tpNumber, tpReq);
        callBybitTradingStop(tpReq);
    }

    private BigDecimal calculatePositionSize(String symbol, BigDecimal price, BigDecimal positionValue) {
        try {
            BigDecimal minQtyFromApi = getMinimumOrderQuantity(symbol);
            // Oblicz ilość na podstawie ceny
            BigDecimal quantity = positionValue.divide(price, 8, RoundingMode.HALF_UP);

            if (quantity.compareTo(minQtyFromApi) < 0) {
                quantity = minQtyFromApi;
            }
            BigDecimal qtyStep = getQuantityStep(symbol);
            quantity = roundToValidQuantity(quantity, qtyStep);

            BigDecimal orderValue = quantity.multiply(price);
            if (orderValue.compareTo(BigDecimal.valueOf(minUsdtAmountForTrade)) < 0) {
                quantity = quantity.add(qtyStep);
            }
            return quantity;
        } catch (Exception e) {
            String baseCoin = extractBaseCoinFromSymbol(symbol);
            BigDecimal minQty = HARD_MIN_QTY_LIMITS.getOrDefault(baseCoin, HARD_MIN_QTY_LIMITS.get("DEFAULT"));
            BigDecimal quantity = positionValue.divide(price, 8, RoundingMode.HALF_UP);
            if (quantity.compareTo(minQty) < 0) {
                quantity = minQty;
            }
            return quantity;
        }
    }

    /**
     * Pobiera ilość kontraktów otwartej pozycji dla danego symbolu i kategorii
     */
    private double getOpenedPositionQty(String symbol, String category) {
        try {
            JsonNode positions = bybitApiClient.getPositions(category, "USDT");
            if (positions.has("result") && positions.get("result").has("list")) {
                for (JsonNode pos : positions.get("result").get("list")) {
                    if (pos.has("symbol") && symbol.equals(pos.get("symbol").asText())) {
                        if (pos.has("size")) {
                            return pos.get("size").asDouble();
                        }
                    }
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Nie udało się pobrać ilości kontraktów dla symbolu: " + symbol, e);
        }
        throw new RuntimeException("Nie znaleziono otwartej pozycji dla symbolu: " + symbol);
    }

    /**
     * Wywołuje endpoint Bybit do ustawienia częściowego TP/SL
     */
    private void callBybitTradingStop(Map<String, Object> tpReq) {
        try {
            bybitApiClient.setTradingStop(tpReq);
        } catch (Exception e) {
            throw new RuntimeException("Nie udało się ustawić częściowego TP/SL: " + tpReq, e);
        }
    }

    /**
     * Pobiera minimalną ilość zamówienia dla danego symbolu
     */
    public BigDecimal getMinimumOrderQuantity(String symbol) throws IOException {
        JsonNode instrumentInfo = bybitApiClient.getInstrumentsInfo("linear", symbol);
        if (instrumentInfo.has("result") && instrumentInfo.get("result").has("list")) {
            JsonNode instrumentList = instrumentInfo.get("result").get("list");
            if (instrumentList.isArray() && instrumentList.size() > 0) {
                JsonNode instrument = instrumentList.get(0);
                if (instrument.has("lotSizeFilter") && instrument.get("lotSizeFilter").has("minOrderQty")) {
                    String minOrderQtyStr = instrument.get("lotSizeFilter").get("minOrderQty").asText();
                    return new BigDecimal(minOrderQtyStr);
                }
            }
        }
        throw new IOException("Nie udało się pobrać minimalnej ilości zamówienia dla symbolu " + symbol);
    }

    /**
     * Pobiera krok ilości (qtyStep) dla danego symbolu z API Bybit
     */
    public BigDecimal getQuantityStep(String symbol) throws IOException {
        JsonNode instrumentInfo = bybitApiClient.getInstrumentsInfo("linear", symbol);
        if (instrumentInfo.has("result") && instrumentInfo.get("result").has("list")) {
            JsonNode instrumentList = instrumentInfo.get("result").get("list");
            if (instrumentList.isArray() && instrumentList.size() > 0) {
                JsonNode instrument = instrumentList.get(0);
                if (instrument.has("lotSizeFilter") && instrument.get("lotSizeFilter").has("qtyStep")) {
                    String qtyStepStr = instrument.get("lotSizeFilter").get("qtyStep").asText();
                    return new BigDecimal(qtyStepStr);
                }
            }
        }
        throw new IOException("Nie udało się pobrać kroku ilości (qtyStep) dla symbolu " + symbol);
    }

    /**
     * Zaokrągla ilość kontraktów zgodnie z wymaganiami dla danego symbolu
     */
    public BigDecimal roundToValidQuantity(BigDecimal quantity, BigDecimal qtyStep) {
        if (qtyStep.compareTo(BigDecimal.ZERO) == 0) {
            return quantity;
        }
        BigDecimal divided = quantity.divide(qtyStep, 0, RoundingMode.UP);
        BigDecimal result = divided.multiply(qtyStep);
        int scale = Math.max(0, qtyStep.scale());
        return result.setScale(scale, RoundingMode.UP);
    }

    /**
     * Ekstrahuje podstawowy coin z symbolu (np. z "1000PEPEUSDT" -> "PEPE")
     */
    public String extractBaseCoinFromSymbol(String symbol) {
        String withoutUSDT = symbol.endsWith("USDT") ? symbol.substring(0, symbol.length() - 4) : symbol;
        if (withoutUSDT.length() > 0) {
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
            if (coinName.length() > 0) {
                return coinName.toString();
            }
        }
        return withoutUSDT;
    }

    public TradingResponseDto openScalpShortPosition(ScalpRequestDto request) {
        log.info("Opening scalp position for request: {}", request);

        String symbol = request.getCoin() + "USDT";
        if (request.getLeverage() != DEFAULT_LEVERAGE) {
            log.info("Zmiana dźwigni z domyślnej {}x na {}x dla {}", DEFAULT_LEVERAGE, request.getLeverage(), symbol);
            setLeverageForSymbol(symbol, request.getLeverage());
        }
        double quantity = (request.getUsdtAmount() * request.getLeverage()) / request.getUsdtPrice();
        BigDecimal rounded = new BigDecimal(quantity).setScale(3, RoundingMode.HALF_UP);
        quantity = rounded.doubleValue();
        log.info("Quantity {}", quantity);

        double retracementPrice = request.getUsdtPrice() * (DEFAULT_TP / request.getLeverage());
        log.info("Retracement: {}", retracementPrice);
        double takeProfit = request.getUsdtPrice() - retracementPrice;
        log.info("Take profit: {}", takeProfit);
        JsonNode orderResponse = bybitApiClient.openPosition(
                "linear",
                symbol,
                "Sell",
                "Market",
                String.valueOf(quantity),
                null,
                String.valueOf(takeProfit),
                null
        );

        var trailingStopValue = String.valueOf(retracementPrice / 2);
        log.info("Trailing stop value: {}", trailingStopValue);

        bybitApiClient.setTrailingStop("linear", symbol, trailingStopValue);

        return TradingResponseDto.builder()
                .orderId(orderResponse.get("result").get("orderId").asText())
                .symbol(symbol)
                .side("Sell")
                .status("SUBMITTED")
                .quantity(quantity)
                .build();
    }
} 