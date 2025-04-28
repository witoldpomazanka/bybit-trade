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
        String coin = (String) payload.getOrDefault("coin", "BTC");
        String type = (String) payload.getOrDefault("type", "LONG");
        Integer leverage = payload.get("leverage") != null ? Integer.parseInt(payload.get("leverage").toString()) : null;
        String takeProfit = (String) payload.get("takeProfit");
        String stopLoss = (String) payload.get("stopLoss");
        // Szukaj TP1-TP5 jeśli nie ma takeProfit
        String[] tps = new String[5];
        for (int i = 1; i <= 5; i++) {
            tps[i-1] = payload.get("tp"+i) != null ? payload.get("tp"+i).toString() : null;
        }
        // Ustaw na sztywno 10 USDT
        double usdtAmount = 10.0;
        // Buduj DTO
        OpenPositionRequestDto req = new OpenPositionRequestDto();
        req.setCoin(coin);
        req.setUsdtAmount(usdtAmount);
        req.setLeverage(leverage);
        if (takeProfit != null) req.setTakeProfit(Double.valueOf(takeProfit));
        if (stopLoss != null) req.setStopLoss(Double.valueOf(stopLoss));
        // LONG/SHORT
        boolean isLong = type.equalsIgnoreCase("LONG");
        // Jeśli jest takeProfit, użyj go, jeśli nie, szukaj tp1-tp5 (tu tylko logika, bo API Bybit nie obsługuje nativnie multi-TP w jednym zleceniu market, więc można tylko logować lub rozbić na kilka zleceń)
        if (takeProfit != null) {
            return isLong ? openLongMarketPosition(req) : openShortMarketPosition(req);
        } else {
            // Jeśli nie ma takeProfit, a są tp1-tp5, można próbować otworzyć pozycję i ustawić TP po kolei (tu tylko logujemy, bo API nie obsługuje multi-TP w jednym zleceniu market)
            for (String tp : tps) {
                if (tp != null) {
                    req.setTakeProfit(Double.valueOf(tp));
                    // Można by tu otwierać osobne pozycje na mniejsze ilości, ale uprośćmy: otwieramy jedną pozycję z pierwszym TP
                    break;
                }
            }
            return isLong ? openLongMarketPosition(req) : openShortMarketPosition(req);
        }
    }
} 