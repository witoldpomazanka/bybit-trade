package com.mulaczos.bybit_trade.service;

import com.mulaczos.bybit_trade.dto.OpenPositionRequestDto;
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
        log.debug("Rozpoczynam otwieranie zaawansowanej pozycji market z payload: {}", payload);
        
        // Walidacja wymaganych pól
        if (!payload.containsKey("coin") || !payload.containsKey("leverage") || !payload.containsKey("type")) {
            log.error("Brak wymaganych pól w payload: {}", payload);
            throw new IllegalArgumentException("Brak wymaganych pól: coin, leverage, type");
        }

        // Podstawowa walidacja i przygotowanie danych
        String coin = payload.get("coin").toString().toUpperCase();
        String type = payload.get("type").toString().toUpperCase();
        log.debug("Przygotowane dane podstawowe - coin: {}, type: {}", coin, type);
        
        if (!type.equals("LONG") && !type.equals("SHORT")) {
            log.error("Nieprawidłowy typ pozycji: {}", type);
            throw new IllegalArgumentException("Type musi być LONG lub SHORT");
        }
        
        Integer leverage = Integer.parseInt(payload.get("leverage").toString());
        String stopLoss = payload.get("stopLoss") != null ? payload.get("stopLoss").toString() : null;
        String takeProfit = payload.get("takeProfit") != null ? payload.get("takeProfit").toString() : null;
        log.debug("Parametry pozycji - leverage: {}, stopLoss: {}, takeProfit: {}", leverage, stopLoss, takeProfit);

        // Pobierz usdtAmount z payload
        BigDecimal usdtAmount = payload.get("usdtAmount") != null ? 
            new BigDecimal(payload.get("usdtAmount").toString()) : 
            MIN_NOTIONAL_VALUE;
        log.debug("Wartość USDT przed dźwignią: {}", usdtAmount);
            
        // Zastosuj dźwignię do usdtAmount
        usdtAmount = usdtAmount.multiply(BigDecimal.valueOf(leverage));
        log.debug("Wartość USDT po zastosowaniu dźwigni ({}x): {}", leverage, usdtAmount);

        // Zbieranie partial take-profits
        Map<Integer, String> partialTps = new HashMap<>();
        for (int i = 1; i <= 5; i++) {
            String tpKey = "tp" + i;
            if (payload.containsKey(tpKey) && payload.get(tpKey) != null) {
                partialTps.put(i, payload.get(tpKey).toString());
            }
        }
        log.debug("Znalezione partial take-profits: {}", partialTps);

        try {
            String symbol = bybitApiClient.findCorrectSymbol(coin);
            log.debug("Znaleziony symbol dla {}: {}", coin, symbol);
            
            if (!bybitApiClient.isSymbolSupported("linear", symbol)) {
                log.error("Symbol {} nie jest obsługiwany w kategorii linear", symbol);
                throw new RuntimeException("Symbol " + symbol + " nie jest obsługiwany w kategorii linear na Bybit");
            }

            // Ustawienie dźwigni
            log.debug("Ustawianie dźwigni {}x dla {}", leverage, symbol);
            bybitApiClient.setLeverage("linear", symbol, String.valueOf(leverage));

            // Przygotowanie podstawowych parametrów zlecenia
            String side = type.equals("LONG") ? "Buy" : "Sell";
            double currentPrice = bybitApiClient.getMarketPrice("linear", symbol);
            BigDecimal price = BigDecimal.valueOf(currentPrice);
            log.debug("Parametry zlecenia - side: {}, currentPrice: {}", side, price);

            // Obliczanie wielkości pozycji z uwzględnieniem usdtAmount
            BigDecimal quantity = calculatePositionSize(symbol, price, usdtAmount);
            String qty = quantity.toString();
            log.debug("Obliczona wielkość pozycji: {}", qty);

            // Otwarcie głównej pozycji
            log.debug("Otwieranie głównej pozycji - symbol: {}, side: {}, qty: {}, takeProfit: {}, stopLoss: {}", 
                     symbol, side, qty, takeProfit, stopLoss);
            JsonNode openResult = bybitApiClient.openPosition(
                "linear",
                symbol,
                side,
                "Market",
                qty,
                null,
                takeProfit,
                stopLoss
            );
            log.debug("Wynik otwarcia pozycji: {}", openResult);

            // Obsługa partial take-profits
            if (!partialTps.isEmpty() && takeProfit == null) {
                double totalQty = getOpenedPositionQty(symbol, "linear");
                int tpCount = partialTps.size();
                log.debug("Konfiguracja partial TP - całkowita ilość: {}, liczba TP: {}", totalQty, tpCount);
                
                // Pobierz minimalny limit dla danej kryptowaluty
                String baseCoin = extractBaseCoinFromSymbol(symbol);
                BigDecimal minQty = HARD_MIN_QTY_LIMITS.getOrDefault(baseCoin, HARD_MIN_QTY_LIMITS.get("DEFAULT"));
                log.debug("Minimalny limit dla {}: {}", baseCoin, minQty);

                // Oblicz równe części dla wszystkich TP oprócz ostatniego
                double basePartSize = Math.floor((totalQty / tpCount) * 100) / 100.0; // Zaokrąglenie do 2 miejsc po przecinku
                double remainingQty = totalQty;
                log.debug("Bazowa wielkość dla każdego TP (oprócz ostatniego): {}", basePartSize);
                
                for (Map.Entry<Integer, String> tp : partialTps.entrySet()) {
                    double tpSize;
                    if (tp.getKey() == tpCount) {
                        // Dla ostatniego TP użyj pozostałej ilości
                        tpSize = Math.round(remainingQty * 100) / 100.0; // Zaokrąglenie do 2 miejsc po przecinku
                        log.debug("Ostatni TP ({}), użycie pozostałej ilości: {}", tp.getKey(), tpSize);
                    } else {
                        tpSize = basePartSize;
                        remainingQty -= basePartSize;
                        log.debug("TP {}, użycie bazowej wielkości: {}, pozostało: {}", tp.getKey(), tpSize, remainingQty);
                    }
                    
                    // Upewnij się, że wielkość TP nie jest mniejsza niż minimalny limit
                    if (BigDecimal.valueOf(tpSize).compareTo(minQty) < 0) {
                        tpSize = minQty.doubleValue();
                        log.debug("Wielkość TP skorygowana do minimalnego limitu: {}", tpSize);
                    }

                    Map<String, Object> tpReq = new HashMap<>();
                    tpReq.put("category", "linear");
                    tpReq.put("symbol", symbol);
                    tpReq.put("tpslMode", "Partial");
                    tpReq.put("tpOrderType", "Market");
                    tpReq.put("tpSize", String.valueOf(tpSize));
                    tpReq.put("takeProfit", tp.getValue());
                    tpReq.put("positionIdx", 0);
                    
                    if (stopLoss != null) {
                        tpReq.put("stopLoss", stopLoss);
                    }
                    
                    log.debug("Ustawianie partial TP {} - parametry: {}", tp.getKey(), tpReq);
                    callBybitTradingStop(tpReq);
                }
            }

            log.debug("Zakończono otwieranie pozycji z sukcesem");
            return openResult;
        } catch (IOException e) {
            log.error("Błąd podczas otwierania pozycji na Bybit: {}", e.getMessage(), e);
            throw new RuntimeException("Błąd podczas otwierania pozycji na Bybit: " + e.getMessage(), e);
        }
    }

    private BigDecimal calculatePositionSize(String symbol, BigDecimal price, BigDecimal notionalValue) throws IOException {
        try {
            BigDecimal minQtyFromApi = getMinimumOrderQuantity(symbol);
            // Oblicz ilość na podstawie ceny
            BigDecimal quantity = notionalValue.divide(price, 8, RoundingMode.HALF_UP);
            
            if (quantity.compareTo(minQtyFromApi) < 0) {
                quantity = minQtyFromApi;
            }
            BigDecimal qtyStep = getQuantityStep(symbol);
            quantity = roundToValidQuantity(quantity, qtyStep);
            
            BigDecimal orderValue = quantity.multiply(price);
            if (orderValue.compareTo(MIN_NOTIONAL_VALUE) < 0) {
                quantity = quantity.add(qtyStep);
            }
            return quantity;
        } catch (Exception e) {
            String baseCoin = extractBaseCoinFromSymbol(symbol);
            BigDecimal minQty = HARD_MIN_QTY_LIMITS.getOrDefault(baseCoin, HARD_MIN_QTY_LIMITS.get("DEFAULT"));
            BigDecimal quantity = notionalValue.divide(price, 8, RoundingMode.HALF_UP);
            if (quantity.compareTo(minQty) < 0) {
                quantity = minQty;
            }
            return quantity;
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
} 