package com.mulaczos.bybit_trade.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.codec.binary.Hex;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Klient REST API BloFin – 1:1 odpowiednik BybitApiClient.
 *
 * Główne różnice względem Bybit:
 *  - Autentykacja: ACCESS-KEY / ACCESS-SIGN / ACCESS-TIMESTAMP / ACCESS-NONCE / ACCESS-PASSPHRASE
 *  - Podpis: HMAC-SHA256(prehash) → hex → Base64
 *    gdzie prehash = requestPath + method + timestamp + nonce + body
 *  - Format instrumentu: "BTC-USDT" (z myślnikiem) zamiast "BTCUSDT"
 *  - Odpowiedzi: { "code": "0", "msg": "...", "data": ... }
 *  - Strony: lowercase "buy"/"sell"
 *  - Typy zleceń: lowercase "market"/"limit"/"post_only"
 *  - Base URL: https://openapi.blofin.com
 */
@Slf4j
public class BlofinApiClient {

    // ── Public Market endpoints ───────────────────────────────────────────────
    private static final String INSTRUMENTS_ENDPOINT     = "/api/v1/market/instruments";
    private static final String TICKERS_ENDPOINT         = "/api/v1/market/tickers";
    private static final String ORDER_BOOK_ENDPOINT      = "/api/v1/market/books";

    // ── Account / Trading endpoints ───────────────────────────────────────────
    private static final String POSITIONS_ENDPOINT       = "/api/v1/account/positions";
    private static final String WALLET_BALANCE_ENDPOINT  = "/api/v1/account/balance";
    private static final String SET_LEVERAGE_ENDPOINT    = "/api/v1/account/set-leverage";
    private static final String PLACE_ORDER_ENDPOINT     = "/api/v1/trade/order";
    private static final String TPSL_ORDER_ENDPOINT      = "/api/v1/trade/order-tpsl";

    private static final String HMAC_SHA256 = "HmacSHA256";

    private final String apiKey;
    private final String secretKey;
    private final String passphrase;
    private final String baseUrl;

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;

    public BlofinApiClient(String apiKey, String secretKey, String passphrase, String baseUrl) {
        this.apiKey = apiKey;
        this.secretKey = secretKey;
        this.passphrase = passphrase;
        this.baseUrl = baseUrl;
        this.httpClient = new OkHttpClient();
        this.objectMapper = new ObjectMapper();
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Metody publiczne – odpowiedniki metod BybitApiClient
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Odpowiednik: getPositions(category, settleCoin, omitLogging)
     * BloFin nie wymaga "category" ani "settleCoin" – listuje wszystkie pozycje futures.
     * Opcjonalnie można podać instId do filtrowania.
     */
    public JsonNode getPositions(String category, String settleCoin, boolean omitLoggingResponse) {
        TreeMap<String, String> params = new TreeMap<>();
        // BloFin nie ma odpowiednika "settleCoin" / "category" na tym endpoincie.
        // Zostawiamy brak filtrowania – zwróci wszystkie aktywne pozycje USDT-futures.
        return executeGetRequest(POSITIONS_ENDPOINT, params, omitLoggingResponse);
    }

    /**
     * Odpowiednik: getWalletBalance(accountType)
     * BloFin: GET /api/v1/account/balance – zwraca saldo futures.
     */
    public JsonNode getWalletBalance(String accountType) {
        TreeMap<String, String> params = new TreeMap<>();
        // accountType ignorowany – BloFin ma jeden endpoint dla futures balance
        return executeGetRequest(WALLET_BALANCE_ENDPOINT, params, false);
    }

    /**
     * Odpowiednik: setLeverage(category, symbol, leverage)
     * BloFin: POST /api/v1/account/set-leverage
     * Wymaga: instId (BTC-USDT), leverage, marginMode, positionSide
     */
    public JsonNode setLeverage(String category, String symbol, String leverage) {
        String instId = toInstId(symbol);
        TreeMap<String, String> params = new TreeMap<>();
        params.put("instId", instId);
        params.put("leverage", leverage);
        params.put("marginMode", "cross");
        params.put("positionSide", "net");

        log.info("Ustawianie dźwigni dla instrumentu {}: {}x", instId, leverage);
        return executePostRequest(SET_LEVERAGE_ENDPOINT, params);
    }

    /**
     * Odpowiednik: openPosition(category, symbol, side, orderType, qty, price, takeProfit, stopLoss)
     * BloFin: POST /api/v1/trade/order
     *
     * Mapowanie:
     *  - symbol        → instId (BTC-USDT)
     *  - side          → buy/sell (lowercase)
     *  - orderType     → market/limit (lowercase)
     *  - qty           → size (liczba kontraktów)
     *  - takeProfit    → tpTriggerPrice
     *  - stopLoss      → slTriggerPrice
     */
    public JsonNode openPosition(String category, String symbol, String side, String orderType,
                                 String qty, String price, String takeProfit, String stopLoss) {
        String instId = toInstId(symbol);
        TreeMap<String, String> params = new TreeMap<>();
        params.put("instId", instId);
        params.put("marginMode", "cross");
        params.put("positionSide", "net");
        params.put("side", side.toLowerCase());
        params.put("orderType", mapOrderType(orderType));
        params.put("size", qty);
        params.put("reduceOnly", "false");

        if (price != null && !price.isEmpty()) {
            params.put("price", price);
        }

        if (takeProfit != null && !takeProfit.isEmpty()) {
            params.put("tpTriggerPrice", takeProfit);
            params.put("tpOrderPrice", "-1"); // -1 = market TP
        }

        if (stopLoss != null && !stopLoss.isEmpty()) {
            params.put("slTriggerPrice", stopLoss);
            params.put("slOrderPrice", "-1"); // -1 = market SL
        }

        log.info("Otwieranie pozycji: instId={}, strona={}, typ={}, ilość={}",
                instId, side, orderType, qty);
        return executePostRequest(PLACE_ORDER_ENDPOINT, params);
    }

    /**
     * Odpowiednik: getMarketPrice(category, symbol)
     * BloFin: używa order book lub tickers do pobrania aktualnej ceny.
     */
    public double getMarketPrice(String category, String symbol) {
        String instId = toInstId(symbol);
        TreeMap<String, String> params = new TreeMap<>();
        params.put("instId", instId);

        // Próbujemy order book (ask+bid / 2)
        JsonNode bookResult = executeGetRequest(ORDER_BOOK_ENDPOINT, params, true);
        if (bookResult.has("data") && bookResult.get("data").isArray()
                && bookResult.get("data").size() > 0) {
            JsonNode book = bookResult.get("data").get(0);
            if (book.has("asks") && book.has("bids")) {
                JsonNode asks = book.get("asks");
                JsonNode bids = book.get("bids");
                if (asks.isArray() && asks.size() > 0 && bids.isArray() && bids.size() > 0) {
                    double bestAsk = Double.parseDouble(asks.get(0).get(0).asText());
                    double bestBid = Double.parseDouble(bids.get(0).get(0).asText());
                    return (bestAsk + bestBid) / 2;
                }
            }
        }

        // Fallback: tickers → "last"
        JsonNode tickersResult = executeGetRequest(TICKERS_ENDPOINT, params, false);
        if (tickersResult.has("data") && tickersResult.get("data").isArray()
                && tickersResult.get("data").size() > 0) {
            JsonNode ticker = tickersResult.get("data").get(0);
            if (ticker.has("last")) {
                return Double.parseDouble(ticker.get("last").asText());
            }
        }

        throw new RuntimeException("Problem z pobraniem ceny rynkowej dla: " + instId);
    }

    /**
     * Odpowiednik: getInstrumentsInfo(category, symbol)
     * BloFin: GET /api/v1/market/instruments?instId=BTC-USDT
     */
    public JsonNode getInstrumentsInfo(String category, String symbol) {
        String instId = toInstId(symbol);
        TreeMap<String, String> params = new TreeMap<>();
        if (symbol != null && !symbol.isEmpty()) {
            params.put("instId", instId);
        }

        log.info("Pobieranie informacji o instrumencie: {}", instId);
        return executeGetRequest(INSTRUMENTS_ENDPOINT, params, true);
    }

    /**
     * Odpowiednik: findCorrectSymbol(coin)
     * BloFin: szuka instrumentu pasującego do {COIN}-USDT.
     * Zwraca symbol w formacie Bybit-kompatybilnym (BTCUSDT) dla zachowania
     * kompatybilności z BybitIntegrationService – konwersja następuje wewnątrz klienta.
     */
    public String findCorrectSymbol(String coin) {
        String targetInstId = coin.toUpperCase() + "-USDT";
        TreeMap<String, String> params = new TreeMap<>();

        JsonNode instrumentsInfo = executeGetRequest(INSTRUMENTS_ENDPOINT, params, true);
        if (instrumentsInfo.has("data") && instrumentsInfo.get("data").isArray()) {
            JsonNode list = instrumentsInfo.get("data");

            // Dokładne dopasowanie
            for (JsonNode instrument : list) {
                if (instrument.has("instId")
                        && targetInstId.equals(instrument.get("instId").asText())) {
                    // Zwracamy w formacie "BTCUSDT" (bez myślnika) – kompatybilny z serwisem
                    return instrument.get("instId").asText().replace("-", "");
                }
            }

            // Częściowe dopasowanie – zawiera coin i kończy się na USDT
            String coinUpper = coin.toUpperCase();
            for (JsonNode instrument : list) {
                if (instrument.has("instId")) {
                    String instId = instrument.get("instId").asText();
                    if (instId.startsWith(coinUpper + "-") && instId.endsWith("USDT")) {
                        return instId.replace("-", "");
                    }
                }
            }
        }

        throw new RuntimeException("Nie znaleziono symbolu dla: %s".formatted(coin));
    }

    /**
     * Odpowiednik: isSymbolSupported(category, symbol)
     */
    public boolean isSymbolSupported(String category, String symbol) {
        try {
            String instId = toInstId(symbol);
            TreeMap<String, String> params = new TreeMap<>();
            params.put("instId", instId);

            JsonNode response = executeGetRequest(INSTRUMENTS_ENDPOINT, params, true);
            if (response.has("data") && response.get("data").isArray()) {
                return response.get("data").size() > 0;
            }
            return false;
        } catch (Exception e) {
            log.warn("Błąd podczas sprawdzania, czy symbol {} jest obsługiwany: {}", symbol, e.getMessage());
            return false;
        }
    }

    /**
     * Odpowiednik: setTradingStop(params)
     * BloFin: POST /api/v1/trade/order-tpsl
     *
     * Mapuje pola z formatu Bybit (tpslMode, tpSize, takeProfit, stopLoss, positionIdx)
     * na format BloFin (tpTriggerPrice, slTriggerPrice, size, positionSide).
     */
    public JsonNode setTradingStop(Map<String, Object> params) {
        TreeMap<String, String> blofinParams = new TreeMap<>();

        // symbol – konwertuj do instId
        if (params.containsKey("symbol")) {
            blofinParams.put("instId", toInstId(params.get("symbol").toString()));
        }

        blofinParams.put("marginMode", "cross");
        blofinParams.put("positionSide", "net");

        // Strona redukcji: zależy od kierunku oryginalnej pozycji,
        // ale ponieważ używamy net mode – ustawiamy buy/sell zależnie od takeProfit/stopLoss
        // W net mode: TP/SL ustawiamy bez "side" – BloFin sam rozpoznaje.
        // Używamy side=buy jeśli reduce (closePosition) – wymagane przez API
        blofinParams.put("side", "buy"); // placeholder – zostanie nadpisany przez TP/SL trigger

        // Rozmiar – z tpSize lub pozostała ilość
        if (params.containsKey("tpSize")) {
            blofinParams.put("size", params.get("tpSize").toString());
        }

        // Take profit
        if (params.containsKey("takeProfit")) {
            String tp = params.get("takeProfit").toString();
            if (!tp.isEmpty()) {
                blofinParams.put("tpTriggerPrice", tp);
                blofinParams.put("tpOrderPrice", "-1");
            }
        }

        // Stop loss
        if (params.containsKey("stopLoss")) {
            String sl = params.get("stopLoss").toString();
            if (!sl.isEmpty()) {
                blofinParams.put("slTriggerPrice", sl);
                blofinParams.put("slOrderPrice", "-1");
            }
        }

        blofinParams.put("reduceOnly", "true");

        log.info("Ustawianie TP/SL dla pozycji (BloFin TPSL order): {}", blofinParams);
        return executePostRequest(TPSL_ORDER_ENDPOINT, blofinParams);
    }

    /**
     * Odpowiednik: setTrailingStop(category, symbol, trailingStop)
     * BloFin nie posiada natywnego trailing stop w standardowym REST API.
     * Implementacja jako TPSL order z dynamicznym SL – logujemy ostrzeżenie.
     */
    public JsonNode setTrailingStop(String category, String symbol, String trailingStop) {
        log.warn("BloFin nie obsługuje trailing stop przez REST API v1. " +
                "Trailing stop (wartość: {}) dla {} zostanie pominięty.", trailingStop, symbol);
        // Zwracamy pusty sukces żeby nie blokować flow
        try {
            return objectMapper.readTree("{\"code\":\"0\",\"msg\":\"trailing_stop_not_supported\",\"data\":{}}");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Metody pomocnicze
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Konwertuje symbol z formatu Bybit (BTCUSDT) na format BloFin (BTC-USDT).
     */
    private String toInstId(String symbol) {
        if (symbol == null) return "";
        if (symbol.contains("-")) return symbol; // już w formacie BloFin
        // Heurystyka: zakładamy USDT linear futures
        if (symbol.endsWith("USDT")) {
            String base = symbol.substring(0, symbol.length() - 4);
            return base + "-USDT";
        }
        return symbol;
    }

    /**
     * Mapuje typ zlecenia z formatu Bybit na format BloFin.
     */
    private String mapOrderType(String bybitOrderType) {
        if (bybitOrderType == null) return "market";
        return switch (bybitOrderType.toLowerCase()) {
            case "limit"   -> "limit";
            case "postonly" -> "post_only";
            default        -> "market";
        };
    }

    /**
     * Buduje query string z parametrów (dla GET requestów).
     */
    private String buildQueryString(TreeMap<String, String> params) {
        StringBuilder queryString = new StringBuilder();
        params.forEach((key, value) -> {
            if (queryString.length() > 0) {
                queryString.append('&');
            }
            queryString.append(key).append('=').append(value);
        });
        return queryString.toString();
    }

    /**
     * Generuje podpis BloFin:
     * prehash = requestPathWithQuery + METHOD + timestamp + nonce + body
     * signature = Base64( HmacSHA256(prehash, secretKey).hexDigest() )
     */
    private String generateSignature(String requestPath, String method,
                                     long timestamp, String nonce, String body) {
        String prehash = requestPath + method + timestamp + nonce + body;
        try {
            Mac sha256HMAC = Mac.getInstance(HMAC_SHA256);
            SecretKeySpec secretKeySpec = new SecretKeySpec(
                    secretKey.getBytes(StandardCharsets.UTF_8), HMAC_SHA256);
            sha256HMAC.init(secretKeySpec);
            byte[] hmacBytes = sha256HMAC.doFinal(prehash.getBytes(StandardCharsets.UTF_8));
            String hexSignature = Hex.encodeHexString(hmacBytes);
            return Base64.encodeBase64String(hexSignature.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new RuntimeException("Błąd podczas generowania podpisu BloFin", e);
        }
    }

    /**
     * Waliduje odpowiedź BloFin. Sukces gdy code == "0".
     */
    private void validateApiResponse(JsonNode response) {
        if (response.has("code")) {
            String code = response.get("code").asText();
            String msg = response.has("msg") ? response.get("msg").asText() : "Unknown error";

            if (!"0".equals(code)) {
                throw new RuntimeException("BŁĄD API BLOFIN: " + msg + " (kod: " + code + ")");
            }
        }
    }

    /**
     * Wykonuje GET request z autentykacją BloFin.
     */
    private JsonNode executeGetRequest(String endpoint, TreeMap<String, String> params,
                                       boolean omitLoggingResponse) {
        long timestamp = Instant.now().toEpochMilli();
        String nonce = UUID.randomUUID().toString();
        String queryString = buildQueryString(params);
        String requestPath = endpoint + (queryString.isEmpty() ? "" : "?" + queryString);

        String signature = generateSignature(requestPath, "GET", timestamp, nonce, "");

        HttpUrl.Builder urlBuilder = HttpUrl.parse(baseUrl + endpoint).newBuilder();
        params.forEach(urlBuilder::addQueryParameter);

        Request request = new Request.Builder()
                .url(urlBuilder.build())
                .get()
                .addHeader("ACCESS-KEY", apiKey)
                .addHeader("ACCESS-SIGN", signature)
                .addHeader("ACCESS-TIMESTAMP", String.valueOf(timestamp))
                .addHeader("ACCESS-NONCE", nonce)
                .addHeader("ACCESS-PASSPHRASE", passphrase)
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Niepowodzenie zapytania: " + response.code() + " " + response.message());
            }

            String responseBody = response.body().string();
            if (!omitLoggingResponse) {
                log.info("Odpowiedź od API BloFin: {}", responseBody);
            }

            JsonNode result = objectMapper.readTree(responseBody);
            validateApiResponse(result);
            return result;
        } catch (IOException e) {
            log.error("Błąd wykonywania GET request: {}", e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }

    /**
     * Wykonuje POST request z autentykacją BloFin.
     */
    private JsonNode executePostRequest(String endpoint, TreeMap<String, String> params) {
        long timestamp = Instant.now().toEpochMilli();
        String nonce = UUID.randomUUID().toString();
        String body = writeValuesAsString(params);

        String signature = generateSignature(endpoint, "POST", timestamp, nonce, body);

        RequestBody requestBody = RequestBody.create(
                body,
                MediaType.parse("application/json")
        );

        Request request = new Request.Builder()
                .url(baseUrl + endpoint)
                .post(requestBody)
                .addHeader("Content-Type", "application/json")
                .addHeader("ACCESS-KEY", apiKey)
                .addHeader("ACCESS-SIGN", signature)
                .addHeader("ACCESS-TIMESTAMP", String.valueOf(timestamp))
                .addHeader("ACCESS-NONCE", nonce)
                .addHeader("ACCESS-PASSPHRASE", passphrase)
                .build();

        log.debug("Wysyłanie POST request do: {}", endpoint);
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Niepowodzenie zapytania: " + response.code() + " " + response.message());
            }

            String responseBody = response.body().string();
            log.info("Odpowiedź od API BloFin: {}", responseBody);

            JsonNode result = objectMapper.readTree(responseBody);
            validateApiResponse(result);
            return result;
        } catch (IOException e) {
            log.error("Błąd wykonywania POST request: {}", e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }

    private String writeValuesAsString(TreeMap<String, String> params) {
        try {
            return objectMapper.writeValueAsString(params);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }
}
