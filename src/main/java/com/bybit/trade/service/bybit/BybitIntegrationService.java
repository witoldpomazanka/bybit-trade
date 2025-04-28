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
    
    // Otwieranie pozycji LONG market
    public JsonNode openLongMarketPosition(OpenPositionRequestDto request) {
        log.info("Otwieranie długiej pozycji Market na Bybit: {}", request);
        return openPosition(request, true, false);
    }
    
    // Otwieranie pozycji SHORT market
    public JsonNode openShortMarketPosition(OpenPositionRequestDto request) {
        log.info("Otwieranie krótkiej pozycji Market na Bybit: {}", request);
        return openPosition(request, false, false);
    }
    
    // Otwieranie pozycji LONG limit
    public JsonNode openLongLimitPosition(OpenPositionRequestDto request) {
        log.info("Otwieranie długiej pozycji Limit na Bybit: {}", request);
        return openPosition(request, true, true);
    }
    
    // Otwieranie pozycji SHORT limit
    public JsonNode openShortLimitPosition(OpenPositionRequestDto request) {
        log.info("Otwieranie krótkiej pozycji Limit na Bybit: {}", request);
        return openPosition(request, false, true);
    }
    
    // Dla kompatybilności
    public JsonNode openMarketPosition(OpenPositionRequestDto request) {
        log.info("Otwieranie pozycji Market (stara metoda) na Bybit: {}", request);
        return openLongMarketPosition(request);
    }
    
    // Dla kompatybilności
    public JsonNode openLimitPosition(OpenPositionRequestDto request) {
        log.info("Otwieranie pozycji Limit (stara metoda) na Bybit: {}", request);
        return openLongLimitPosition(request);
    }

    private JsonNode openPosition(OpenPositionRequestDto request, boolean isLong, boolean isPostOnlyLimit) {
        try {
            // Pobieramy podstawowy coin
            String coin = request.getCoin().toUpperCase();
            
            // Wyszukaj prawidłowy symbol kontraktu
            String symbol;
            try {
                symbol = bybitApiClient.findCorrectSymbol(coin);
                log.info("Znaleziono prawidłowy symbol dla coina {}: {}", coin, symbol);
            } catch (IOException e) {
                log.error("Nie znaleziono prawidłowego symbolu dla coina {}: {}", coin, e.getMessage());
                throw new RuntimeException("Nie znaleziono prawidłowego symbolu dla coina " + coin + ". Sprawdź, czy ten coin jest dostępny na Bybit.", e);
            }
            
            log.info("Otwieranie pozycji na Bybit: {} (symbol: {})", request, symbol);
            
            // Sprawdź, czy symbol jest obsługiwany
            if (!bybitApiClient.isSymbolSupported("linear", symbol)) {
                log.error("Symbol {} nie jest obsługiwany w kategorii linear na Bybit", symbol);
                throw new RuntimeException("Symbol " + symbol + " nie jest obsługiwany w kategorii linear na Bybit");
            }
            
            // Ustalenie dźwigni
            int leverage = request.getLeverage() != null ? request.getLeverage() : DEFAULT_LEVERAGE;
            log.info("Używanie dźwigni {}x dla symbolu {}", leverage, symbol);
            
            // Ustawienie dźwigni przed otwarciem pozycji
            JsonNode leverageResult = bybitApiClient.setLeverage("linear", symbol, String.valueOf(leverage));
            log.info("Wynik ustawienia dźwigni: {}", leverageResult);
            
            String side = isLong ? "Buy" : "Sell";
            String orderType = isPostOnlyLimit ? "Limit" : "Market";
            
            // Pobieranie aktualnej ceny rynkowej
            double currentPrice;
            try {
                currentPrice = getCurrentPrice(symbol);
                log.info("Aktualna cena rynkowa dla {}: {}", symbol, currentPrice);
            } catch (IOException e) {
                log.error("Nie udało się pobrać aktualnej ceny rynkowej dla {}: {}", symbol, e.getMessage());
                throw new RuntimeException("Nie udało się pobrać aktualnej ceny rynkowej dla " + symbol, e);
            }
            
            // Obliczanie ilości kontraktów na podstawie kwoty USDT i aktualnej ceny
            BigDecimal price = BigDecimal.valueOf(currentPrice);
            BigDecimal usdtAmount = BigDecimal.valueOf(request.getUsdtAmount());
            
            // Sprawdzenie, czy kwota USDT spełnia minimalne wymagania
            if (usdtAmount.compareTo(MIN_NOTIONAL_VALUE) < 0) {
                log.warn("Kwota USDT {} jest mniejsza niż minimalna wymagana wartość {}. Ustawiam minimalną wartość.", 
                    usdtAmount, MIN_NOTIONAL_VALUE);
                usdtAmount = MIN_NOTIONAL_VALUE;
            }
            
            // Obliczenie ilości kontraktów
            BigDecimal quantity = usdtAmount.divide(price, 8, RoundingMode.HALF_UP);
            
            // Pobierz minimalny limit z API Bybit
            try {
                BigDecimal minQtyFromApi = getMinimumOrderQuantity(symbol);
                log.info("Pobrany z API minimalny limit dla {}: {}", symbol, minQtyFromApi);
                
                if (quantity.compareTo(minQtyFromApi) < 0) {
                    log.warn("Obliczona ilość {} jest mniejsza niż minimalny limit {} pobrany z API dla {}. " +
                             "Ustawiam minimalną wartość wymaganą przez Bybit.", quantity, minQtyFromApi, symbol);
                    quantity = minQtyFromApi;
                    
                    // Obliczamy też rzeczywistą kwotę USDT po dostosowaniu do minimalnego limitu
                    BigDecimal actualUsdtAmount = minQtyFromApi.multiply(price);
                    log.info("Rzeczywista kwota USDT po dostosowaniu do minimalnego limitu: {}", 
                             actualUsdtAmount.setScale(2, RoundingMode.HALF_UP));
                }
                
                // Pobierz krok ilości (qtyStep) i zaokrąglij ilość
                BigDecimal qtyStep = getQuantityStep(symbol);
                log.info("Pobrany z API krok ilości (qtyStep) dla {}: {}", symbol, qtyStep);
                
                // Zaokrąglij ilość do prawidłowej wartości
                BigDecimal originalQuantity = quantity;
                quantity = roundToValidQuantity(quantity, qtyStep);
                
                if (originalQuantity.compareTo(quantity) != 0) {
                    log.info("Zaokrąglono ilość z {} do {} zgodnie z krokiem ilości {} dla {}", 
                            originalQuantity, quantity, qtyStep, symbol);
                }
                
                // Sprawdź, czy po zaokrągleniu wartość zlecenia spełnia minimalne wymagania
                BigDecimal orderValue = quantity.multiply(price);
                if (orderValue.compareTo(MIN_NOTIONAL_VALUE) < 0) {
                    log.warn("Wartość zlecenia {} USDT jest mniejsza niż minimalna wymagana wartość {} USDT po zaokrągleniu ilości. Dodaję jeden krok ilości.", 
                            orderValue.setScale(2, RoundingMode.HALF_UP), MIN_NOTIONAL_VALUE);
                    
                    // Dodaj jeden krok ilości do obecnej wartości
                    quantity = quantity.add(qtyStep);
                    
                    log.info("Dostosowano ilość z {} do {} (dodano jeden krok {}), co daje wartość zlecenia {} USDT", 
                            quantity.subtract(qtyStep), quantity, qtyStep, 
                            quantity.multiply(price).setScale(2, RoundingMode.HALF_UP));
                }
                
            } catch (Exception e) {
                // Jeśli wystąpi błąd podczas pobierania danych z API, użyj zdefiniowanych wcześniej limitów
                log.warn("Nie udało się pobrać minimalnego limitu z API dla {}. Używam predefiniowanej wartości.", symbol, e);
                
                // Wyciągnij bazowy coin z symbolu (np. z "1000PEPEUSDT" -> "PEPE")
                String baseCoin = extractBaseCoinFromSymbol(symbol);
                
                BigDecimal minQty = HARD_MIN_QTY_LIMITS.getOrDefault(baseCoin, HARD_MIN_QTY_LIMITS.get("DEFAULT"));
                if (quantity.compareTo(minQty) < 0) {
                    log.warn("Obliczona ilość {} jest mniejsza niż minimalny limit {} narzucony przez Bybit dla {}. " +
                             "Ustawiam minimalną wartość wymaganą przez Bybit.", quantity, minQty, baseCoin);
                    quantity = minQty;
                    
                    // Obliczamy też rzeczywistą kwotę USDT po dostosowaniu do minimalnego limitu
                    BigDecimal actualUsdtAmount = minQty.multiply(price);
                    log.info("Rzeczywista kwota USDT po dostosowaniu do minimalnego limitu: {}", 
                             actualUsdtAmount.setScale(2, RoundingMode.HALF_UP));
                }
            }
            
            // Zaokrąglenie ilości kontraktów do odpowiedniej skali 
            String qty = quantity.toString();
            
            log.info("Przeliczono kwotę {} USDT na {} kontraktów przy cenie {}", 
                     request.getUsdtAmount(), qty, currentPrice);
            
            String takeProfit = request.getTakeProfit() != null ? 
                request.getTakeProfit().toString() : null;
            String stopLoss = request.getStopLoss() != null ? 
                request.getStopLoss().toString() : null;

            JsonNode result;
            if (isPostOnlyLimit) {
                String limitPrice = calculateLimitPrice(currentPrice, side);
                
                log.info("Otwieranie pozycji Post-Only Limit: symbol={}, strona={}, typ={}, ilość={}, cena={}", 
                    symbol, side, orderType, qty, limitPrice);
                
                result = bybitApiClient.openPosition(
                    "linear",
                    symbol,
                    side,
                    orderType,
                    qty,
                    limitPrice,
                    takeProfit,
                    stopLoss
                );
            } else {
                log.info("Otwieranie pozycji Market: symbol={}, strona={}, typ={}, ilość={}", 
                    symbol, side, orderType, qty);
                
                result = bybitApiClient.openPosition(
                    "linear",
                    symbol,
                    side,
                    orderType,
                    qty,
                    null,
                    takeProfit,
                    stopLoss
                );
            }
            
            log.info("Otwarto pozycję na Bybit: {}", result);
            return result;
        } catch (IOException e) {
            log.error("Błąd podczas otwierania pozycji na Bybit: {}", request, e);
            throw new RuntimeException("Nie udało się otworzyć pozycji na Bybit", e);
        }
    }

    private double getCurrentPrice(String symbol) throws IOException {
        log.info("Pobieranie aktualnej ceny rynkowej dla {}", symbol);
        try {
            double price = bybitApiClient.getMarketPrice("linear", symbol);
            log.info("Pobrano aktualną cenę rynkową dla {}: {}", symbol, price);
            return price;
        } catch (IOException e) {
            log.error("Błąd podczas pobierania ceny rynkowej dla {}: {}", symbol, e.getMessage());
            throw new IOException("Nie udało się pobrać ceny rynkowej dla symbolu " + symbol + ": " + e.getMessage(), e);
        }
    }

    private String calculateLimitPrice(double currentPrice, String side) {
        BigDecimal price = BigDecimal.valueOf(currentPrice);
        BigDecimal offset = price.multiply(BigDecimal.valueOf(PRICE_OFFSET_PERCENTAGE))
            .divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_UP);
        
        if (side.equals("Buy")) {
            price = price.subtract(offset);
        } else {
            price = price.add(offset);
        }
        
        return price.setScale(2, RoundingMode.HALF_UP).toString();
    }

    private BigDecimal getMinimumOrderQuantity(String symbol) throws IOException {
        // Pobierz informacje o instrumencie z API Bybit
        JsonNode instrumentInfo = bybitApiClient.getInstrumentsInfo("linear", symbol);
        
        if (instrumentInfo.has("result") && instrumentInfo.get("result").has("list")) {
            JsonNode instrumentList = instrumentInfo.get("result").get("list");
            if (instrumentList.isArray() && instrumentList.size() > 0) {
                JsonNode instrument = instrumentList.get(0);
                
                // Sprawdź, czy instrument zawiera informacje o lotSizeFilter i minOrderQty
                if (instrument.has("lotSizeFilter") && instrument.get("lotSizeFilter").has("minOrderQty")) {
                    String minOrderQtyStr = instrument.get("lotSizeFilter").get("minOrderQty").asText();
                    return new BigDecimal(minOrderQtyStr);
                }
            }
        }
        
        // Jeśli nie udało się znaleźć danych, rzuć wyjątek
        throw new IOException("Nie udało się pobrać minimalnej ilości zamówienia dla symbolu " + symbol);
    }
    
    /**
     * Pobiera krok ilości (qtyStep) dla danego symbolu z API Bybit
     */
    private BigDecimal getQuantityStep(String symbol) throws IOException {
        // Pobierz informacje o instrumencie z API Bybit
        JsonNode instrumentInfo = bybitApiClient.getInstrumentsInfo("linear", symbol);
        
        if (instrumentInfo.has("result") && instrumentInfo.get("result").has("list")) {
            JsonNode instrumentList = instrumentInfo.get("result").get("list");
            if (instrumentList.isArray() && instrumentList.size() > 0) {
                JsonNode instrument = instrumentList.get(0);
                
                // Sprawdź, czy instrument zawiera informacje o lotSizeFilter i qtyStep
                if (instrument.has("lotSizeFilter") && instrument.get("lotSizeFilter").has("qtyStep")) {
                    String qtyStepStr = instrument.get("lotSizeFilter").get("qtyStep").asText();
                    return new BigDecimal(qtyStepStr);
                }
            }
        }
        
        // Jeśli nie udało się znaleźć danych, rzuć wyjątek
        throw new IOException("Nie udało się pobrać kroku ilości (qtyStep) dla symbolu " + symbol);
    }
    
    /**
     * Zaokrągla ilość kontraktów zgodnie z wymaganiami dla danego symbolu
     */
    private BigDecimal roundToValidQuantity(BigDecimal quantity, BigDecimal qtyStep) {
        if (qtyStep.compareTo(BigDecimal.ZERO) == 0) {
            return quantity; // Unikamy dzielenia przez zero
        }
        
        // Zaokrąglamy w dół do najbliższej wielokrotności qtyStep
        BigDecimal divided = quantity.divide(qtyStep, 0, RoundingMode.DOWN);
        BigDecimal result = divided.multiply(qtyStep);
        
        // Upewniamy się, że liczba miejsc po przecinku nie przekracza skali qtyStep
        int scale = Math.max(0, qtyStep.scale());
        return result.setScale(scale, RoundingMode.DOWN);
    }
    
    /**
     * Ekstrahuje podstawowy coin z symbolu (np. z "1000PEPEUSDT" -> "PEPE")
     */
    private String extractBaseCoinFromSymbol(String symbol) {
        // Usuwamy "USDT" z końca
        String withoutUSDT = symbol.endsWith("USDT") ? symbol.substring(0, symbol.length() - 4) : symbol;
        
        // Sprawdź, czy nazwa zawiera prefiks liczbowy (np. 1000PEPE)
        if (withoutUSDT.length() > 0) {
            StringBuilder coinName = new StringBuilder();
            boolean foundLetter = false;
            
            for (char c : withoutUSDT.toCharArray()) {
                if (Character.isLetter(c)) {
                    coinName.append(c);
                    foundLetter = true;
                } else if (foundLetter) {
                    // Jeśli znaleźliśmy już literę i napotkamy na cyfrę, to znaczy że to koniec nazwy coina
                    coinName.append(c);
                }
            }
            
            if (coinName.length() > 0) {
                return coinName.toString();
            }
        }
        
        // Fallback - zwróć oryginalny symbol bez USDT
        return withoutUSDT;
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
        boolean isLong = type.equalsIgnoreCase("LONG");
        // 1. Brak TP i brak tpX -> pozycja bez TP
        if (takeProfit == null && tpCount == 0) {
            if (stopLoss != null) req.setStopLoss(Double.valueOf(stopLoss));
            return isLong ? openLongMarketPosition(req) : openShortMarketPosition(req);
        }
        // 2. Jest tylko takeProfit (i brak tpX) -> pełny TP
        if (takeProfit != null && tpCount == 0) {
            req.setTakeProfit(Double.valueOf(takeProfit));
            if (stopLoss != null) req.setStopLoss(Double.valueOf(stopLoss));
            return isLong ? openLongMarketPosition(req) : openShortMarketPosition(req);
        }
        // 3. Są tp1-tp5 (może być też takeProfit, ale ignorujemy go na rzecz tpX)
        // Otwórz pozycję market na całość
        if (stopLoss != null) req.setStopLoss(Double.valueOf(stopLoss));
        JsonNode openResult = isLong ? openLongMarketPosition(req) : openShortMarketPosition(req);
        // Pozycja otwarta, teraz częściowe TP
        if (tpCount > 0) {
            // Pobierz wielkość pozycji (można założyć, że to ilość kontraktów z openResult lub z wyliczenia)
            // Tu uproszczenie: dzielimy całość na równe części
            double[] tpPercents = new double[tpCount];
            for (int i = 0; i < tpCount; i++) tpPercents[i] = 1.0 / tpCount;
            // Pobierz symbol (po otwarciu pozycji symbol może być np. 1000PEPEUSDT, więc najlepiej wyciągnąć z openResult lub ponownie znaleźć symbol)
            String symbol = findSymbolAfterOpen(coin);
            String category = findCategoryAfterOpen(symbol);
            double totalQty = getOpenedPositionQty(symbol, category); // do zaimplementowania: pobierz ilość kontraktów z otwartej pozycji
            for (int i = 0; i < tpCount; i++) {
                double tpSize = Math.floor(totalQty * tpPercents[i]);
                if (i == tpCount - 1) {
                    // Ostatni TP dostaje resztę (żeby suma się zgadzała)
                    tpSize = totalQty - Math.floor(totalQty * tpPercents[0]) * (tpCount - 1);
                }
                // Przygotuj request na częściowy TP
                Map<String, Object> tpReq = new java.util.HashMap<>();
                tpReq.put("category", category);
                tpReq.put("symbol", symbol);
                tpReq.put("tpslMode", "Partial");
                tpReq.put("tpOrderType", "Market");
                tpReq.put("tpSize", String.valueOf((int)tpSize));
                tpReq.put("takeProfit", tps[i]);
                tpReq.put("positionIdx", 0); // one-way mode
                if (i == 0 && stopLoss != null) {
                    tpReq.put("stopLoss", stopLoss);
                }
                // Wywołaj endpoint /v5/position/trading-stop (do zaimplementowania metoda callBybitTradingStop(tpReq))
                callBybitTradingStop(tpReq);
            }
        }
        return openResult;
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
} 