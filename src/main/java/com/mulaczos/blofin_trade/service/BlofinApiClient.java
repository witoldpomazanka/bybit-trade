package com.mulaczos.blofin_trade.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mulaczos.blofin_trade.exception.BlofinApiException;
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
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Klient REST API BloFin – 1:1 odpowiednik starego klienta giełdy.
 *
 * Różnice:
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

    private static final String INSTRUMENTS_ENDPOINT = "/api/v1/market/instruments";
    private static final String TICKERS_ENDPOINT = "/api/v1/market/tickers";
    private static final String ORDER_BOOK_ENDPOINT = "/api/v1/market/books";

    private static final String POSITIONS_ENDPOINT = "/api/v1/account/positions";
    private static final String WALLET_BALANCE_ENDPOINT = "/api/v1/account/balance";
    private static final String SET_LEVERAGE_ENDPOINT = "/api/v1/account/set-leverage";
    private static final String PLACE_ORDER_ENDPOINT = "/api/v1/trade/order";
    private static final String ORDER_DETAILS_ENDPOINT = "/api/v1/trade/order-details";
    private static final String BATCH_ORDERS_ENDPOINT = "/api/v1/trade/batch-orders";
    private static final String ALGO_ORDER_ENDPOINT = "/api/v1/trade/order-algo";
    private static final String TPSL_ORDER_ENDPOINT = "/api/v1/trade/order-tpsl";

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

    public JsonNode getPositions(String category, String settleCoin, boolean omitLoggingResponse) {
        TreeMap<String, String> params = new TreeMap<>();
        return executeGetRequest(POSITIONS_ENDPOINT, params, omitLoggingResponse);
    }

    public JsonNode getWalletBalance(String accountType) {
        TreeMap<String, String> params = new TreeMap<>();
        return executeGetRequest(WALLET_BALANCE_ENDPOINT, params, false);
    }

    public JsonNode setLeverage(String category, String symbol, String leverage) {
        String instId = toInstId(symbol);
        TreeMap<String, String> params = new TreeMap<>();
        params.put("instId", instId);
        params.put("leverage", leverage);
        params.put("marginMode", "isolated");
        params.put("positionSide", "net");

        log.info("Ustawianie dźwigni dla instrumentu {}: {}x", instId, leverage);
        return executePostRequest(SET_LEVERAGE_ENDPOINT, params);
    }

    public JsonNode openPosition(String category, String symbol, String side, String orderType,
                                 String qty, String price, String takeProfit, String stopLoss) {
        String instId = toInstId(symbol);
        TreeMap<String, String> params = new TreeMap<>();
        params.put("instId", instId);
        params.put("marginMode", "isolated");
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
            params.put("tpOrderPrice", "-1");
        }

        if (stopLoss != null && !stopLoss.isEmpty()) {
            params.put("slTriggerPrice", stopLoss);
            params.put("slOrderPrice", "-1");
        }

        log.info("Otwieranie pozycji: instId={}, strona={}, typ={}, ilość={}",
                instId, side, orderType, qty);
        return executePostRequest(PLACE_ORDER_ENDPOINT, params);
    }

    public double getMarketPrice(String category, String symbol) {
        String instId = toInstId(symbol);
        TreeMap<String, String> params = new TreeMap<>();
        params.put("instId", instId);

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

    public JsonNode getInstrumentsInfo(String category, String symbol) {
        String instId = toInstId(symbol);
        TreeMap<String, String> params = new TreeMap<>();
        if (symbol != null && !symbol.isEmpty()) {
            params.put("instId", instId);
        }

        log.info("Pobieranie informacji o instrumencie: {}", instId);
        return executeGetRequest(INSTRUMENTS_ENDPOINT, params, true);
    }

    public String findCorrectSymbol(String coin) {
        String targetInstId = coin.toUpperCase() + "-USDT";
        TreeMap<String, String> params = new TreeMap<>();

        JsonNode instrumentsInfo = executeGetRequest(INSTRUMENTS_ENDPOINT, params, true);
        if (instrumentsInfo.has("data") && instrumentsInfo.get("data").isArray()) {
            JsonNode list = instrumentsInfo.get("data");

            for (JsonNode instrument : list) {
                if (instrument.has("instId")
                        && targetInstId.equals(instrument.get("instId").asText())) {
                    return instrument.get("instId").asText().replace("-", "");
                }
            }

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

    public JsonNode setTradingStop(Map<String, Object> params) {
        TreeMap<String, String> blofinParams = new TreeMap<>();

        if (params.containsKey("symbol")) {
            blofinParams.put("instId", toInstId(params.get("symbol").toString()));
        }

        blofinParams.put("marginMode", "isolated");
        blofinParams.put("positionSide", "net");
        blofinParams.put("side", "buy");

        if (params.containsKey("tpSize")) {
            blofinParams.put("size", params.get("tpSize").toString());
        }

        if (params.containsKey("takeProfit")) {
            String tp = params.get("takeProfit").toString();
            if (!tp.isEmpty()) {
                blofinParams.put("tpTriggerPrice", tp);
                blofinParams.put("tpOrderPrice", "-1");
            }
        }

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

    public JsonNode setTrailingStop(String category, String symbol, String trailingStop) {
        log.warn("BloFin nie obsługuje trailing stop przez REST API v1. " +
                "Trailing stop (wartość: {}) dla {} zostanie pominięty.", trailingStop, symbol);
        try {
            return objectMapper.readTree("{\"code\":\"0\",\"msg\":\"trailing_stop_not_supported\",\"data\":{}}");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public JsonNode getOrderDetails(String symbol, String orderId) {
        String instId = toInstId(symbol);
        TreeMap<String, String> params = new TreeMap<>();
        params.put("instId", instId);
        params.put("orderId", orderId);
        return executeGetRequest(ORDER_DETAILS_ENDPOINT, params, false);
    }

    public JsonNode placeBatchOrders(List<Map<String, String>> orders) {
        // Blofin batch orders expects an array of order objects in the body
        try {
            String body = objectMapper.writeValueAsString(orders);
            return executePostRequestWithRawBody(BATCH_ORDERS_ENDPOINT, body);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Błąd podczas serializacji batch orders", e);
        }
    }

    public JsonNode placeAlgoOrder(String symbol, String side, String orderType, String size, String triggerPrice, String orderPrice, boolean reduceOnly) {
        String instId = toInstId(symbol);
        TreeMap<String, String> params = new TreeMap<>();
        params.put("instId", instId);
        params.put("side", side.toLowerCase());
        params.put("orderType", orderType.toLowerCase()); // e.g., "stop_market"
        params.put("size", size);
        params.put("reduceOnly", String.valueOf(reduceOnly));
        params.put("triggerPrice", triggerPrice);
        if (orderPrice != null && !orderPrice.isEmpty()) {
            params.put("price", orderPrice);
        } else {
            params.put("price", "-1"); // -1 typically means market for algo orders if type is stop_market
        }

        log.info("Składanie zlecenia algo: instId={}, side={}, type={}, size={}, trigger={}",
                instId, side, orderType, size, triggerPrice);
        return executePostRequest(ALGO_ORDER_ENDPOINT, params);
    }

    private JsonNode executePostRequestWithRawBody(String endpoint, String body) {
        long timestamp = Instant.now().toEpochMilli();
        String nonce = UUID.randomUUID().toString();

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

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Niepowodzenie zapytania: " + response.code() + " " + response.message());
            }

            String responseBody = response.body().string();
            log.info("Odpowiedź od API BloFin (batch/raw): {}", responseBody);

            JsonNode result = objectMapper.readTree(responseBody);
            validateApiResponse(result);
            return result;
        } catch (IOException e) {
            log.error("Błąd wykonywania POST request (raw body): {}", e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }

    private String toInstId(String symbol) {
        if (symbol == null) return "";
        if (symbol.contains("-")) return symbol;
        if (symbol.endsWith("USDT")) {
            String base = symbol.substring(0, symbol.length() - 4);
            return base + "-USDT";
        }
        return symbol;
    }

    private String mapOrderType(String orderType) {
        if (orderType == null) return "market";
        return switch (orderType.toLowerCase()) {
            case "limit" -> "limit";
            case "postonly" -> "post_only";
            default -> "market";
        };
    }

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

    private void validateApiResponse(JsonNode response) {
        if (!response.has("code")) return;

        String topCode = response.get("code").asText();
        if ("0".equals(topCode)) return;

        // Próbuj wyciągnąć szczegóły błędu z pierwszego elementu tablicy data[]
        String detailCode = topCode;
        String detailMsg = response.has("msg") ? response.get("msg").asText() : "Unknown error";

        if (response.has("data") && response.get("data").isArray() && response.get("data").size() > 0) {
            JsonNode first = response.get("data").get(0);
            if (first.has("code") && !"0".equals(first.get("code").asText())) {
                detailCode = first.get("code").asText();
            }
            if (first.has("msg") && !first.get("msg").asText().isBlank()) {
                detailMsg = first.get("msg").asText();
            }
        }

        log.error("BloFin API zwrócił błąd: code={}, msg={}", detailCode, detailMsg);
        throw new BlofinApiException(detailCode, detailMsg);
    }

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
