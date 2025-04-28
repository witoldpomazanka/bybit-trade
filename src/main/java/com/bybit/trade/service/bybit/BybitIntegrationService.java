package com.bybit.trade.service.bybit;

import com.bybit.trade.dto.OpenPositionRequestDto;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

    private static final double PRICE_OFFSET_PERCENTAGE = 0.01; // 0.01% offset dla Post-Only Limit
    private static final BigDecimal MIN_NOTIONAL_VALUE = new BigDecimal("5.0"); // Minimalna wartość zamówienia w USDT
    private static final int DEFAULT_LEVERAGE = 2; // Domyślna dźwignia x2
    
    // Twarde minimalne limity narzucone przez Bybit (nie możemy używać mniejszych wartości)
    private static final Map<String, BigDecimal> HARD_MIN_QTY_LIMITS = new HashMap<>();
    static {
        HARD_MIN_QTY_LIMITS.put("BTC", new BigDecimal("0.001")); // Minimalny limit dla BTC to 0.001
        HARD_MIN_QTY_LIMITS.put("ETH", new BigDecimal("0.01"));  // Minimalny limit dla ETH to 0.01
        HARD_MIN_QTY_LIMITS.put("SOL", new BigDecimal("0.1"));   // Minimalny limit dla SOL to 0.1
        // Domyślny limit dla innych kryptowalut
        HARD_MIN_QTY_LIMITS.put("DEFAULT", new BigDecimal("0.01"));
    }
    
    private final BybitApiClient bybitApiClient;

    public JsonNode getOpenPositions() {
        try {
            log.info("Pobieranie otwartych pozycji z Bybit");
            JsonNode result = bybitApiClient.getPositions("linear", "USDT");
            log.info("Pobrano dane o otwartych pozycjach: {}", result);
            return result;
        } catch (IOException e) {
            log.error("Błąd podczas pobierania otwartych pozycji z Bybit", e);
            throw new RuntimeException("Nie udało się pobrać otwartych pozycji z Bybit", e);
        }
    }

    public JsonNode getAccountBalance() {
        try {
            log.info("Pobieranie salda konta z Bybit");
            JsonNode result = bybitApiClient.getWalletBalance("UNIFIED");
            log.info("Pobrano dane o saldzie konta: {}", result);
            return result;
        } catch (IOException e) {
            log.error("Błąd podczas pobierania salda konta z Bybit", e);
            throw new RuntimeException("Nie udało się pobrać salda konta z Bybit", e);
        }
    }

    public JsonNode openAdvancedMarketPosition(Map<String, Object> payload) {
        // Walidacja wymaganych pól
        if (!payload.containsKey("coin") || !payload.containsKey("leverage") || !payload.containsKey("type")) {
            throw new IllegalArgumentException("Brak wymaganych pól: coin, leverage, type");
        }
        String coin = payload.get("coin").toString();
        String type = payload.get("type").toString();
        Integer leverage = payload.get("leverage") != null ? Integer.parseInt(payload.get("leverage").toString()) : null;
        String takeProfit = payload.get("takeProfit") != null ? payload.get("takeProfit").toString() : null;
        String stopLoss = payload.get("stopLoss") != null ? payload.get("stopLoss").toString() : null;
        // Szukaj TP1-TP5 jeśli nie ma takeProfit
        String[] tps = new String[5];
        int tpCount = 0;
        for (int i = 1; i <= 5; i++) {
            Object tpVal = payload.get("tp"+i);
            if (tpVal != null) {
                tps[tpCount++] = tpVal.toString();
            }
        }
        double margin = payload.get("usdtAmount") != null ? Double.parseDouble(payload.get("usdtAmount").toString()) : 10.0;
        double usdtAmount = margin;
        if (leverage != null && leverage > 0) {
            usdtAmount = margin * leverage;
        }
        OpenPositionRequestDto req = new OpenPositionRequestDto();
        req.setCoin(coin);
        req.setUsdtAmount(usdtAmount);
        req.setLeverage(leverage);
        if (stopLoss != null) req.setStopLoss(Double.valueOf(stopLoss));
        if (takeProfit != null) req.setTakeProfit(Double.valueOf(takeProfit));
        boolean isLong = type.equalsIgnoreCase("LONG");
        // --- Zamiast openLongMarketPosition/openShortMarketPosition ---
        try {
            String symbol = bybitApiClient.findCorrectSymbol(coin);
            if (!bybitApiClient.isSymbolSupported("linear", symbol)) {
                throw new RuntimeException("Symbol " + symbol + " nie jest obsługiwany w kategorii linear na Bybit");
            }
            int lev = leverage != null ? leverage : DEFAULT_LEVERAGE;
            bybitApiClient.setLeverage("linear", symbol, String.valueOf(lev));
            String side = isLong ? "Buy" : "Sell";
            double currentPrice = bybitApiClient.getMarketPrice("linear", symbol);
            BigDecimal price = BigDecimal.valueOf(currentPrice);
            BigDecimal usdtAmountBD = BigDecimal.valueOf(usdtAmount);
            if (usdtAmountBD.compareTo(MIN_NOTIONAL_VALUE) < 0) {
                usdtAmountBD = MIN_NOTIONAL_VALUE;
            }
            BigDecimal quantity = usdtAmountBD.divide(price, 8, RoundingMode.HALF_UP);
            try {
                BigDecimal minQtyFromApi = getMinimumOrderQuantity(symbol);
                if (quantity.compareTo(minQtyFromApi) < 0) {
                    quantity = minQtyFromApi;
                }
                BigDecimal qtyStep = getQuantityStep(symbol);
                quantity = roundToValidQuantity(quantity, qtyStep);
                BigDecimal orderValue = quantity.multiply(price);
                if (orderValue.compareTo(MIN_NOTIONAL_VALUE) < 0) {
                    quantity = quantity.add(qtyStep);
                }
            } catch (Exception e) {
                String baseCoin = extractBaseCoinFromSymbol(symbol);
                BigDecimal minQty = HARD_MIN_QTY_LIMITS.getOrDefault(baseCoin, HARD_MIN_QTY_LIMITS.get("DEFAULT"));
                if (quantity.compareTo(minQty) < 0) {
                    quantity = minQty;
                }
            }
            String qty = quantity.toString();
            String takeProfitStr = req.getTakeProfit() != null ? req.getTakeProfit().toString() : null;
            String stopLossStr = req.getStopLoss() != null ? req.getStopLoss().toString() : null;
            JsonNode openResult = bybitApiClient.openPosition(
                "linear",
                symbol,
                side,
                "Market",
                qty,
                null,
                takeProfitStr,
                stopLossStr
            );
            // --- multi-TP ---
            if (tpCount > 0) {
                double[] tpPercents = new double[tpCount];
                for (int i = 0; i < tpCount; i++) tpPercents[i] = 1.0 / tpCount;
                String category = findCategoryAfterOpen(symbol);
                double totalQty = getOpenedPositionQty(symbol, category);
                for (int i = 0; i < tpCount; i++) {
                    double tpSize = Math.floor(totalQty * tpPercents[i]);
                    if (i == tpCount - 1) {
                        tpSize = totalQty - Math.floor(totalQty * tpPercents[0]) * (tpCount - 1);
                    }
                    Map<String, Object> tpReq = new java.util.HashMap<>();
                    tpReq.put("category", category);
                    tpReq.put("symbol", symbol);
                    tpReq.put("tpslMode", "Partial");
                    tpReq.put("tpOrderType", "Market");
                    tpReq.put("tpSize", String.valueOf((int)tpSize));
                    tpReq.put("takeProfit", tps[i]);
                    tpReq.put("positionIdx", 0);
                    if (i == 0 && stopLoss != null) {
                        tpReq.put("stopLoss", stopLoss);
                    }
                    callBybitTradingStop(tpReq);
                }
            }
            return openResult;
        } catch (IOException e) {
            throw new RuntimeException("Błąd podczas otwierania pozycji na Bybit", e);
        }
    }

    // --- METODY POMOCNICZE DO CZĘŚCIOWYCH TP ---
    /**
     * Zwraca symbol kontraktu po otwarciu pozycji (na podstawie coina)
     */
    private String findSymbolAfterOpen(String coin) {
        try {
            return bybitApiClient.findCorrectSymbol(coin);
        } catch (Exception e) {
            throw new RuntimeException("Nie udało się znaleźć symbolu dla coina: " + coin, e);
        }
    }

    /**
     * Zwraca kategorię kontraktu (zawsze 'linear' dla USDT)
     */
    private String findCategoryAfterOpen(String symbol) {
        return "linear";
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
        BigDecimal divided = quantity.divide(qtyStep, 0, RoundingMode.DOWN);
        BigDecimal result = divided.multiply(qtyStep);
        int scale = Math.max(0, qtyStep.scale());
        return result.setScale(scale, RoundingMode.DOWN);
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
} 