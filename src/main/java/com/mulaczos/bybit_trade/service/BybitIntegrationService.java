package com.mulaczos.bybit_trade.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.mulaczos.bybit_trade.dto.AdvancedMarketPositionRequest;
import com.mulaczos.bybit_trade.dto.ScalpRequestDto;
import com.mulaczos.bybit_trade.dto.TradingResponseDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
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

    @Value("${retracement.divider:2}")
    private Double retracementDivider;

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
        return openAdvancedMarketPosition(AdvancedMarketPositionRequest.fromMap(payload));
    }

    public JsonNode openAdvancedMarketPosition(AdvancedMarketPositionRequest request) {
        log.info("Rozpoczynam otwieranie zaawansowanej pozycji market: {}", request);
        try {
            String symbol = prepareAndValidateSymbol(request.getCoin());
            setLeverageForSymbol(symbol, request.getLeverage());
            JsonNode openResult = openMainPosition(symbol, request);
            if (shouldConfigurePartialTakeProfits(request)) {
                configurePartialTakeProfits(symbol, request);
            } else {
                log.info("Pominięto konfigurację partial take-profits - nie są wymagane");
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
        log.info("Pobieranie aktualnej ceny rynkowej dla {}", symbol);
        double currentPrice = bybitApiClient.getMarketPrice("linear", symbol);
        BigDecimal price = BigDecimal.valueOf(currentPrice);
        log.info("Aktualna cena rynkowa dla {}: {}", symbol, price);

        // Obliczanie wielkości pozycji
        BigDecimal positionValueInUsdtAfterLeverageMultiply = getPositionValueInUsdtAfterLeverageMultiply(request);

        BigDecimal quantityInCrypto = calculatePositionSize(symbol, price, positionValueInUsdtAfterLeverageMultiply);
        log.info("Obliczona wielkość pozycji: {}", quantityInCrypto);

        // Otwarcie pozycji
        log.info("Przygotowanie parametrów zlecenia");
        log.info("Parametry zlecenia - symbol: {}, side: {}, qty: {}, takeProfit: {}, stopLoss: {}",
                symbol, request.getSide(), quantityInCrypto, request.getTakeProfit(), request.getStopLoss());

        log.info("Wysyłanie zlecenia do Bybit");
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

    @NotNull
    private BigDecimal getPositionValueInUsdtAfterLeverageMultiply(AdvancedMarketPositionRequest request) {
        BigDecimal bigDecimal = request.getUsdtAmount() != null ? request.getUsdtAmount().multiply(BigDecimal.valueOf(request.getLeverage())) :
                BigDecimal.valueOf(minUsdtAmountForTrade).multiply(BigDecimal.valueOf(request.getLeverage()));
        if (request.getUsdtAmount() != null) {
            log.info("[getUsdtAmount] Wartość pozycji w USDT po uwzględnieniu dźwigni (dźwignia * cena z requestu): {}", bigDecimal);
        } else {
            log.info("[minUsdtAmountForTrade] Wartość pozycji w USDT po uwzględnieniu dźwigni (dźwignia * cena minimalna): {}", bigDecimal);
        }
        return bigDecimal;
    }

    private boolean shouldConfigurePartialTakeProfits(AdvancedMarketPositionRequest request) {
        return request.hasPartialTakeProfits() && request.getTakeProfit() == null;
    }

    private void configurePartialTakeProfits(String symbol, AdvancedMarketPositionRequest request) throws IOException {
        log.info("Rozpoczynam konfigurację partial take-profits");

        log.info("Pobieranie całkowitej ilości otwartej pozycji");
        double totalQty = getOpenedPositionQty(symbol, "linear");
        Map<Integer, String> partialTps = request.getPartialTakeProfits();
        int tpCount = partialTps.size();

        log.info("Dane do konfiguracji partial TP - całkowita ilość: {}, liczba TP: {}", totalQty, tpCount);

        // Pobierz minimalny limit dla danej kryptowaluty
        String baseCoin = extractBaseCoinFromSymbol(symbol);
        BigDecimal minQty = HARD_MIN_QTY_LIMITS.getOrDefault(baseCoin, HARD_MIN_QTY_LIMITS.get("DEFAULT"));
        log.info("Minimalny limit dla {}: {}", baseCoin, minQty);

        // Oblicz równe części dla wszystkich TP oprócz ostatniego
        double basePartSize = Math.floor((totalQty / tpCount) * 100) / 100.0;
        double remainingQty = totalQty;
        log.info("Bazowa wielkość dla każdego TP (oprócz ostatniego): {}, pozostała ilość: {}", basePartSize, remainingQty);

        for (Map.Entry<Integer, String> tp : partialTps.entrySet()) {
            log.info("Konfiguracja TP numer {} z {} - cena: {}", tp.getKey(), tpCount, tp.getValue());
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
                log.info("Pozostała ilość po TP {}: {}", tp.getKey(), remainingQty);
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
        log.info("Rozpoczynam obliczanie wielkości pozycji dla symbolu: {}", symbol);
        log.info("Parametry wejściowe - cena: {}, wartość pozycji: {}", price, positionValue);

        try {
            log.info("Pobieranie minimalnej ilości zamówienia z API");
            BigDecimal minQtyFromApi = getMinimumOrderQuantity(symbol);
            log.info("Minimalna ilość zamówienia z API: {}", minQtyFromApi);

            // Oblicz ilość na podstawie ceny
            log.info("Obliczanie podstawowej ilości na podstawie ceny i wartości pozycji");
            BigDecimal quantity = positionValue.divide(price, 8, RoundingMode.HALF_UP);
            log.info("Podstawowa ilość przed walidacją: {}", quantity);

            if (quantity.compareTo(minQtyFromApi) < 0) {
                log.info("Ilość poniżej minimalnej - koryguję do minimalnej wartości");
                quantity = minQtyFromApi;
            }

            log.info("Pobieranie kroku ilości (qtyStep)");
            BigDecimal qtyStep = getQuantityStep(symbol);
            log.info("Krok ilości (qtyStep): {}", qtyStep);

            log.info("Zaokrąglanie ilości do prawidłowej wartości");
            quantity = roundToValidQuantity(quantity, qtyStep);
            log.info("Ilość po zaokrągleniu: {}", quantity);

            // Sprawdzanie minimalnej wartości zamówienia w USDT tylko gdy używamy domyślnej wartości lub podana wartość jest za mała
            if (positionValue.compareTo(BigDecimal.valueOf(minUsdtAmountForTrade).multiply(BigDecimal.valueOf(DEFAULT_LEVERAGE))) <= 0) {
                log.info("Sprawdzanie minimalnej wartości zamówienia w USDT");
                BigDecimal orderValue = quantity.multiply(price);
                log.info("Wartość zamówienia w USDT: {}", orderValue);
                log.info("Minimalna wymagana wartość w USDT: {}", minUsdtAmountForTrade);

                if (orderValue.compareTo(BigDecimal.valueOf(minUsdtAmountForTrade)) < 0) {
                    log.info("Wartość zamówienia poniżej minimalnej - ustawiam minimalną wartość");
                    quantity = BigDecimal.valueOf(minUsdtAmountForTrade).divide(price, 8, RoundingMode.UP);
                    quantity = roundToValidQuantity(quantity, qtyStep);
                    log.info("Skorygowana ilość: {}", quantity);
                }
            }

            log.info("Obliczanie wielkości pozycji zakończone - wynik: {}", quantity);
            return quantity;
        } catch (Exception e) {
            log.warn("Wystąpił błąd podczas obliczania wielkości pozycji - używam wartości domyślnych");
            String baseCoin = extractBaseCoinFromSymbol(symbol);
            BigDecimal minQty = HARD_MIN_QTY_LIMITS.getOrDefault(baseCoin, HARD_MIN_QTY_LIMITS.get("DEFAULT"));
            log.info("Używam minimalnej ilości dla {}: {}", baseCoin, minQty);

            BigDecimal quantity = positionValue.divide(price, 8, RoundingMode.HALF_UP);
            log.info("Obliczona ilość przed walidacją: {}", quantity);

            if (quantity.compareTo(minQty) < 0) {
                log.info("Ilość poniżej minimalnej - koryguję do minimalnej wartości");
                quantity = minQty;
            }

            log.info("Zwracam ilość po korekcie: {}", quantity);
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

        var trailingStopValue = String.valueOf(retracementPrice / retracementDivider);
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