package com.mulaczos.bybit_trade.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mulaczos.bybit_trade.dto.AdvancedMarketPositionRequest;
import com.mulaczos.bybit_trade.dto.ScalpRequestDto;
import com.mulaczos.bybit_trade.dto.TradingResponseDto;
import com.mulaczos.bybit_trade.model.TradeHistory;
import com.mulaczos.bybit_trade.repository.TradeHistoryRepository;
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
public class BybitIntegrationService {

    private static final int DEFAULT_LEVERAGE = 10;
    private static int CURRENT_LEVERAGE = DEFAULT_LEVERAGE;
    private static final double DEFAULT_TP = 0.05;
    @Value("${min-usdt-amount-for-trade}")
    private Double minUsdtAmountForTrade;

    @Value("${retracement.divider:2}")
    private Double retracementDivider;

    private final BlofinApiClient bybitApiClient; // BloFin client – interfejs 1:1 z Bybit
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final TwilioNotificationService twilioNotificationService;
    private final LimitOrderService limitOrderService;
    private final TradeHistoryRepository tradeHistoryRepository;

    @EventListener(ApplicationStartedEvent.class)
    public void init() {
        log.info("Inicjalizacja BloFin - ustawianie domyślnej dźwigni {}x", DEFAULT_LEVERAGE);
        setLeverageForSymbol("BTCUSDT", DEFAULT_LEVERAGE);
        log.info("Wartość w USDT dla minimalnego domyślnego zagrania, jeśli nie zdefiniowane w payload: {}", minUsdtAmountForTrade);
    }

    public JsonNode getOpenPositions() {
        log.info("Pobieranie otwartych pozycji z BloFin");
        JsonNode result = bybitApiClient.getPositions("linear", "USDT", false);
        log.info("Pobrano dane o otwartych pozycjach: {}", result);
        return result;
    }

    public JsonNode getAccountBalance() {
        log.info("Pobieranie salda konta z BloFin");
        JsonNode result = bybitApiClient.getWalletBalance("UNIFIED");
        log.info("Pobrano dane o saldzie konta: {}", result);
        return result;
    }

    @Transactional
    public JsonNode openAdvancedPosition(Map<String, Object> payload, String chatTitle) {
        return openAdvancedPosition(AdvancedMarketPositionRequest.fromMap(payload), chatTitle);
    }

    @Transactional
    public JsonNode openAdvancedPosition(AdvancedMarketPositionRequest request, String chatTitle) {
        log.info("Rozpoczynam otwieranie zaawansowanej pozycji: {}", request);
        try {
            String symbol = prepareAndValidateSymbol(request.getCoin());

            // Sprawdzenie czy istnieje już pozycja dla tego symbolu z tego czatu
            if (chatTitle != null) {
                Optional<TradeHistory> existingTrade = tradeHistoryRepository.findFirstBySymbolAndChatTitleOrderByCreatedAtDesc(symbol, chatTitle);
                if (existingTrade.isPresent()) {
                    log.warn("Znaleziono istniejącą pozycję dla symbolu {} z czatu {} - pomijam otwieranie nowej pozycji", symbol, chatTitle);
                    return createErrorResponse("Dla symbolu " + symbol + " z czatu " + chatTitle + " istnieje już otwarta pozycja.");
                }
            }

            JsonNode check = checkIfThePositionForSymbolIsAlreadyOpened(symbol);
            if (check != null) return check;

            setLeverageForSymbol(symbol, request.getLeverage());

            // Specjalna obsługa dla zleceń limit
            if (request.isLimit()) {
                log.info("Wykryto zlecenie limit - specjalna obsługa");

                // Obliczanie wielkości pozycji
                BigDecimal quantityInCrypto = calculatePositionSize(symbol, BigDecimal.valueOf(Double.parseDouble(request.getEntryPrice())),
                        request.getUsdtAmount() != null ?
                                request.getUsdtAmount().multiply(BigDecimal.valueOf(request.getLeverage())) :
                                BigDecimal.valueOf(minUsdtAmountForTrade).multiply(BigDecimal.valueOf(request.getLeverage())));

                // Otwarcie pozycji
                String orderType = "Limit";
                String orderPrice = request.getEntryPrice();

                log.info("Parametry zlecenia limit - symbol: {}, side: {}, cena: {}, qty: {}, stopLoss: {}",
                        symbol, request.getSide(), orderPrice, quantityInCrypto, request.getStopLoss());

                JsonNode openResult = bybitApiClient.openPosition(
                        "linear",
                        symbol,
                        request.getSide(),
                        orderType,
                        quantityInCrypto.toString(),
                        orderPrice,
                        null, // Brak bezpośredniego TP w zleceniu limit
                        request.getStopLoss()
                );

                // Zapisywanie historii zlecenia
                saveTradeHistory(symbol, request, quantityInCrypto.toString(), chatTitle, orderType, orderPrice);

                // BloFin: data jest tablicą [{ orderId, clientOrderId }]
                if (openResult.has("data") && openResult.get("data").isArray()
                        && openResult.get("data").size() > 0
                        && openResult.get("data").get(0).has("orderId")) {
                    String orderId = openResult.get("data").get(0).get("orderId").asText();
                    limitOrderService.saveLimitOrder(orderId, request, symbol, quantityInCrypto.toString());
                }
                sendSms(request, symbol, openResult);
                return openResult;
            } else {
                // Standardowa obsługa dla zleceń market
                JsonNode openResult = openPosition(symbol, request);

                // BloFin: qty pobieramy z pola size przekazanego w zleceniu (data nie zawiera qty w response)
                String executedQty = openResult.has("data") && openResult.get("data").isArray()
                        && openResult.get("data").size() > 0
                        && openResult.get("data").get(0).has("qty")
                        ? openResult.get("data").get(0).get("qty").asText()
                        : "unknown";
                saveTradeHistory(symbol, request, executedQty, chatTitle, "Market", null);

                if (shouldConfigurePartialTakeProfits(request)) {
                    configurePartialTakeProfits(symbol, request);
                } else {
                    log.info("Pominięto konfigurację partial take-profits - nie są wymagane");
                }
                log.info("Zakończono otwieranie pozycji z sukcesem");

                // Wysyłanie powiadomienia SMS
                sendSms(request, symbol, openResult);

                return openResult;
            }
        } catch (IOException e) {
            log.error("Błąd podczas otwierania pozycji na Bybit: {}", e.getMessage(), e);
            throw new RuntimeException("Błąd podczas otwierania pozycji na Bybit: " + e.getMessage(), e);
        }
    }

    private void saveTradeHistory(String symbol, AdvancedMarketPositionRequest request, String quantity, String chatTitle, String orderType, String entryPrice) {
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
                .usdtAmount(request.getUsdtAmount() != null ? request.getUsdtAmount().toString() : null)
                .build();

        tradeHistoryRepository.save(tradeHistory);
        log.info("Zapisano historię zlecenia: {}", tradeHistory);
    }

    private void sendSms(AdvancedMarketPositionRequest request, String symbol, JsonNode openResult) {
        // BloFin: data jest tablicą, qty nie jest zwracane w response – używamy wartości z requestu
        String qty = "nieznana";
        if (openResult.has("data") && openResult.get("data").isArray()
                && openResult.get("data").size() > 0
                && openResult.get("data").get(0).has("qty")) {
            qty = openResult.get("data").get(0).get("qty").asText();
        }
        twilioNotificationService.sendPositionOpenedNotification(
                symbol,
                request.getSide(),
                qty,
                request.getLeverage(),
                request.isLimit() ? "Limit" : "Market"
        );
    }

    @Nullable
    private JsonNode checkIfThePositionForSymbolIsAlreadyOpened(String symbol) {
        log.info("Sprawdzam czy istnieje już otwarta pozycja dla symbolu: {}", symbol);
        JsonNode openPositions = getOpenPositions();
        // BloFin: { "code": "0", "data": [ { "instId": "BTC-USDT", "positions": "1", ... } ] }
        if (openPositions.has("data") && openPositions.get("data").isArray()) {
            JsonNode positionsList = openPositions.get("data");
            // BloFin używa instId w formacie BTC-USDT; symbol w serwisie to BTCUSDT
            String instId = symbol.contains("-") ? symbol : toInstId(symbol);
            for (JsonNode position : positionsList) {
                String posInstId = position.has("instId") ? position.get("instId").asText() : "";
                // positions: ilość kontraktów (ujemna = short)
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

    /** Konwertuje BTCUSDT → BTC-USDT dla porównań z BloFin instId */
    private String toInstId(String symbol) {
        if (symbol == null || symbol.contains("-")) return symbol;
        if (symbol.endsWith("USDT")) {
            return symbol.substring(0, symbol.length() - 4) + "-USDT";
        }
        return symbol;
    }

    private String prepareAndValidateSymbol(String coin) throws IOException {
        log.info("Szukanie symbolu dla coina: {}", coin);
        String symbol = bybitApiClient.findCorrectSymbol(coin);
        if (!bybitApiClient.isSymbolSupported("linear", symbol)) {
            throw new RuntimeException("Symbol " + symbol + " nie jest obsługiwany na BloFin");
        }
        log.info("Znaleziony i zwalidowany symbol: {}", symbol);
        return symbol;
    }

    private void setLeverageForSymbol(String symbol, int leverage) {
        log.info("Ustawianie dźwigni {}x dla symbolu {}", leverage, symbol);
        bybitApiClient.setLeverage("linear", symbol, String.valueOf(leverage));
        CURRENT_LEVERAGE = leverage;
    }

    private JsonNode openPosition(String symbol, AdvancedMarketPositionRequest request) throws IOException {
        log.info("Rozpoczynam przygotowanie pozycji");

        // Przygotowanie parametrów zlecenia
        log.info("Pobieranie aktualnej ceny rynkowej dla {}", symbol);
        double currentPrice = bybitApiClient.getMarketPrice("linear", symbol);
        BigDecimal price = BigDecimal.valueOf(currentPrice);
        log.info("Aktualna cena rynkowa dla {}: {}", symbol, price);

        // Obliczanie wielkości pozycji
        log.info("Obliczanie wartości pozycji w USDT");
        BigDecimal positionValueInUsdtAfterLeverageMultiply;
        if (request.getUsdtAmount() != null) {
            positionValueInUsdtAfterLeverageMultiply = request.getUsdtAmount().multiply(BigDecimal.valueOf(request.getLeverage()));
            log.info("Używam wartości z requestu: {}", positionValueInUsdtAfterLeverageMultiply);
        } else {
            positionValueInUsdtAfterLeverageMultiply = BigDecimal.valueOf(minUsdtAmountForTrade).multiply(BigDecimal.valueOf(request.getLeverage()));
            log.info("Używam wartości minimalnej: {}", positionValueInUsdtAfterLeverageMultiply);
        }
        log.info("Wartość pozycji po uwzględnieniu dźwigni: {}", positionValueInUsdtAfterLeverageMultiply);

        BigDecimal quantityInCrypto = calculatePositionSize(symbol, price, positionValueInUsdtAfterLeverageMultiply);
        log.info("Obliczona wielkość pozycji: {}", quantityInCrypto);

        // Określenie typu zlecenia
        String orderType = request.isLimit() ? "Limit" : "Market";
        String orderPrice = request.isLimit() ? request.getEntryPrice() : null;

        // Otwarcie pozycji
        log.info("Parametry zlecenia - symbol: {}, side: {}, typ: {}, cena: {}, qty: {}, takeProfit: {}, stopLoss: {}, obecna dźwignia: x{}",
                symbol, request.getSide(), orderType, orderPrice, quantityInCrypto, request.getTakeProfit(), request.getStopLoss(), CURRENT_LEVERAGE);

        // Obliczanie kwoty, która zostanie pobrana z konta
        BigDecimal valueToDeduct;
        if (request.isLimit()) {
            // Dla zleceń limit używamy ceny limitu
            valueToDeduct = quantityInCrypto.multiply(new BigDecimal(request.getEntryPrice()))
                    .divide(new BigDecimal(CURRENT_LEVERAGE), 8, RoundingMode.HALF_UP);
            log.info("Jeśli zlecenie LIMIT zostanie w pełni zrealizowane, z konta zostanie pobrane około {} USDT (cena limit: {}, ilość: {})",
                    valueToDeduct, request.getEntryPrice(), quantityInCrypto);
        } else {
            // Dla zleceń market używamy aktualnej ceny rynkowej
            valueToDeduct = quantityInCrypto.multiply(price)
                    .divide(new BigDecimal(CURRENT_LEVERAGE), 8, RoundingMode.HALF_UP);
            log.info("Z konta zostanie pobrane {} USDT", valueToDeduct);
        }

        log.info("Wysyłanie zlecenia do Bybit");
        return bybitApiClient.openPosition(
                "linear",
                symbol,
                request.getSide(),
                orderType,
                quantityInCrypto.toString(),
                orderPrice,
                request.getTakeProfit(),
                request.getStopLoss()
        );
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
        BigDecimal minQty = getMinimumOrderQuantity(symbol);
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

    /**
     * Pobiera minimalną wartość zlecenia w USDT z API Bybit
     */
    public BigDecimal getMinOrderValue(String symbol) throws IOException {
        JsonNode instrumentInfo = bybitApiClient.getInstrumentsInfo("linear", symbol);
        // BloFin: { "data": [ { "minSize": "0.1", "tickSize": "0.5", ... } ] }
        if (instrumentInfo.has("data") && instrumentInfo.get("data").isArray()
                && instrumentInfo.get("data").size() > 0) {
            JsonNode instrument = instrumentInfo.get("data").get(0);
            // BloFin nie ma minNotionalValue – obliczamy z minSize * tickSize (przybliżenie)
            // Używamy contractValue * minSize jako minimalna wartość
            if (instrument.has("minSize") && instrument.has("tickSize")) {
                BigDecimal minSize = new BigDecimal(instrument.get("minSize").asText());
                BigDecimal tickSize = new BigDecimal(instrument.get("tickSize").asText());
                // Zwracamy minSize * tickSize jako minimalną wartość zlecenia (w USDT)
                // W praktyce minimalna wartość to minSize * aktualnej ceny, ale tickSize daje dolne ograniczenie
                return minSize.multiply(tickSize);
            }
        }
        throw new IOException("Nie udało się pobrać minimalnej wartości zlecenia dla symbolu " + symbol);
    }

    private BigDecimal calculatePositionSize(String symbol, BigDecimal price, BigDecimal positionValue) throws IOException {
        log.info("Rozpoczynam obliczanie wielkości pozycji dla symbolu: {}", symbol);
        log.info("Parametry wejściowe - cena: {}, wartość pozycji po uwzględnieniu dźwigni: {}", price, positionValue);

        log.info("Pobieranie minimalnej ilości zamówienia z API");
        BigDecimal minQtyFromApi = getMinimumOrderQuantity(symbol);
        log.info("Minimalna ilość zamówienia z API: {}", minQtyFromApi);

        log.info("Pobieranie minimalnej wartości zlecenia w USDT z API");
        BigDecimal minOrderValue = getMinOrderValue(symbol);
        log.info("Minimalna wartość zlecenia w USDT: {}", minOrderValue);

        // Oblicz ilość na podstawie ceny
        log.info("Obliczanie podstawowej ilości na podstawie ceny i wartości pozycji");
        BigDecimal quantity = positionValue.divide(price, 8, RoundingMode.HALF_UP);
        log.info("Podstawowa ilość przed walidacją: {}", quantity);

        BigDecimal orderValue = quantity.multiply(price);
        log.info("Wartość zlecenia w USDT z uwzlgędnieniem dźwigni x{}: {}", orderValue, CURRENT_LEVERAGE);

        if (orderValue.compareTo(minOrderValue) < 0) {
            log.info("Wartość zlecenia poniżej minimalnej ({} USDT) - koryguję ilość", minOrderValue);
            quantity = minOrderValue.divide(price, 8, RoundingMode.UP);
            log.info("Skorygowana ilość po uwzględnieniu minimalnej wartości USDT: {}", quantity);
        }

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

        log.info("Obliczanie wielkości pozycji zakończone - wynik: {}", quantity);
        return quantity;
    }

    /**
     * Pobiera ilość kontraktów otwartej pozycji dla danego symbolu i kategorii
     */
    private double getOpenedPositionQty(String symbol, String category) {
        try {
            JsonNode positions = bybitApiClient.getPositions(category, "USDT", false);
            // BloFin: { "data": [ { "instId": "BTC-USDT", "positions": "1" } ] }
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
        // BloFin: { "data": [ { "minSize": "0.1", "lotSize": "0.1", ... } ] }
        if (instrumentInfo.has("data") && instrumentInfo.get("data").isArray()
                && instrumentInfo.get("data").size() > 0) {
            JsonNode instrument = instrumentInfo.get("data").get(0);
            if (instrument.has("minSize")) {
                return new BigDecimal(instrument.get("minSize").asText());
            }
        }
        throw new IOException("Nie udało się pobrać minimalnej ilości zamówienia dla symbolu " + symbol);
    }

    /**
     * Pobiera krok ilości (qtyStep) dla danego symbolu z API Bybit
     */
    public BigDecimal getQuantityStep(String symbol) throws IOException {
        JsonNode instrumentInfo = bybitApiClient.getInstrumentsInfo("linear", symbol);
        // BloFin: { "data": [ { "lotSize": "0.1", ... } ] } – lotSize to krok ilości
        if (instrumentInfo.has("data") && instrumentInfo.get("data").isArray()
                && instrumentInfo.get("data").size() > 0) {
            JsonNode instrument = instrumentInfo.get("data").get(0);
            if (instrument.has("lotSize")) {
                return new BigDecimal(instrument.get("lotSize").asText());
            }
        }
        throw new IOException("Nie udało się pobrać kroku ilości (lotSize) dla symbolu " + symbol);
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

        log.info("Zakończono otwieranie pozycji scalp z sukcesem");

        // Wysyłanie powiadomienia SMS
        sendScalpSms(request, symbol, quantity);

        // BloFin: data jest tablicą [{ orderId, clientOrderId }]
        String orderId = orderResponse.has("data") && orderResponse.get("data").isArray()
                && orderResponse.get("data").size() > 0
                && orderResponse.get("data").get(0).has("orderId")
                ? orderResponse.get("data").get(0).get("orderId").asText()
                : "unknown";

        return TradingResponseDto.builder()
                .orderId(orderId)
                .symbol(symbol)
                .side("Sell")
                .status("SUBMITTED")
                .quantity(quantity)
                .build();
    }

    private void sendScalpSms(ScalpRequestDto request, String symbol, double quantity) {
        twilioNotificationService.sendPositionOpenedNotification(
                symbol,
                "Sell",
                String.valueOf(quantity),
                request.getLeverage(),
                "Market"
        );
    }

    private JsonNode createErrorResponse(String errorMessage) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("BŁĄD", errorMessage);
        return response;
    }
} 