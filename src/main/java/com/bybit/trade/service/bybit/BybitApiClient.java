package com.bybit.trade.service.bybit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.apache.commons.codec.binary.Hex;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.TreeMap;

@Slf4j
@Service
public class BybitApiClient {

    private static final String POSITIONS_ENDPOINT = "/v5/position/list";
    private static final String WALLET_BALANCE_ENDPOINT = "/v5/account/wallet-balance";
    private static final String PLACE_ORDER_ENDPOINT = "/v5/order/create";
    private static final String ORDERBOOK_ENDPOINT = "/v5/market/orderbook";
    private static final String TICKERS_ENDPOINT = "/v5/market/tickers";
    private static final String SET_LEVERAGE_ENDPOINT = "/v5/position/set-leverage";
    private static final String HMAC_SHA256 = "HmacSHA256";
    
    private final String apiKey;
    private final String secretKey;
    private final String baseUrl;
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;

    public BybitApiClient(String apiKey, String secretKey, String baseUrl) {
        this.apiKey = apiKey;
        this.secretKey = secretKey;
        this.baseUrl = baseUrl;
        this.httpClient = new OkHttpClient();
        this.objectMapper = new ObjectMapper();
    }

    public JsonNode getPositions(String category, String settleCoin) throws IOException {
        TreeMap<String, String> params = new TreeMap<>();
        params.put("category", category);
        params.put("settleCoin", settleCoin);
        
        return executeGetRequest(POSITIONS_ENDPOINT, params);
    }

    public JsonNode getWalletBalance(String accountType) throws IOException {
        TreeMap<String, String> params = new TreeMap<>();
        params.put("accountType", accountType);
        
        return executeGetRequest(WALLET_BALANCE_ENDPOINT, params);
    }

    public JsonNode setLeverage(String category, String symbol, String leverage) throws IOException {
        TreeMap<String, String> params = new TreeMap<>();
        params.put("category", category);
        params.put("symbol", symbol);
        params.put("buyLeverage", leverage);
        params.put("sellLeverage", leverage);
        
        log.info("Ustawianie dźwigni dla symbolu {}: {}x", symbol, leverage);
        return executePostRequest(SET_LEVERAGE_ENDPOINT, params);
    }

    public JsonNode openPosition(String category, String symbol, String side, String orderType, 
                                String qty, String price, String takeProfit, String stopLoss) throws IOException {
        TreeMap<String, String> params = new TreeMap<>();
        params.put("category", category);
        params.put("symbol", symbol);
        params.put("side", side);
        params.put("orderType", orderType);
        params.put("qty", qty);
        
        if (price != null && !price.isEmpty()) {
            params.put("price", price);
        }
        
        if (takeProfit != null && !takeProfit.isEmpty()) {
            params.put("takeProfit", takeProfit);
        }
        
        if (stopLoss != null && !stopLoss.isEmpty()) {
            params.put("stopLoss", stopLoss);
        }
        
        params.put("timeInForce", orderType.equals("Limit") ? "PostOnly" : "GTC");
        params.put("positionIdx", "0");
        params.put("reduceOnly", "false");
        
        log.info("Otwieranie pozycji: symbol={}, strona={}, typ={}, ilość={}", symbol, side, orderType, qty);
        return executePostRequest(PLACE_ORDER_ENDPOINT, params);
    }

    public double getMarketPrice(String category, String symbol) throws IOException {
        TreeMap<String, String> params = new TreeMap<>();
        params.put("category", category);
        params.put("symbol", symbol);

        try {
            // Najpierw próbujemy pobrać cenę z order booka
            JsonNode orderBookResult = executeGetRequest(ORDERBOOK_ENDPOINT, params);
            if (orderBookResult.has("result") && orderBookResult.get("result").has("a") && orderBookResult.get("result").has("b")) {
                // Pobierz najlepszą cenę ask i bid
                double bestAsk = Double.parseDouble(orderBookResult.get("result").get("a").get(0).get(0).asText());
                double bestBid = Double.parseDouble(orderBookResult.get("result").get("b").get(0).get(0).asText());
                // Zwróć średnią cenę
                return (bestAsk + bestBid) / 2;
            }
        } catch (Exception e) {
            log.warn("Nie udało się pobrać ceny z order booka, próbuję z tickerów", e);
        }

        // Jeśli nie udało się pobrać z order booka, próbujemy z tickerów
        JsonNode tickersResult = executeGetRequest(TICKERS_ENDPOINT, params);
        if (tickersResult.has("result") && tickersResult.get("result").has("list")) {
            JsonNode firstTicker = tickersResult.get("result").get("list").get(0);
            if (firstTicker.has("lastPrice")) {
                return Double.parseDouble(firstTicker.get("lastPrice").asText());
            }
        }

        throw new IOException("Nie udało się pobrać ceny rynkowej");
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

    private String generateSignature(long timestamp, String queryString) {
        String payload = timestamp + apiKey + "5000" + queryString;
        try {
            Mac sha256_HMAC = Mac.getInstance(HMAC_SHA256);
            SecretKeySpec secret_key = new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), HMAC_SHA256);
            sha256_HMAC.init(secret_key);
            return Hex.encodeHexString(sha256_HMAC.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new RuntimeException("Błąd podczas generowania podpisu", e);
        }
    }

    private JsonNode executeGetRequest(String endpoint, TreeMap<String, String> params) throws IOException {
        long timestamp = Instant.now().toEpochMilli();
        String queryString = buildQueryString(params);
        
        String signature = generateSignature(timestamp, queryString);
        
        HttpUrl.Builder urlBuilder = HttpUrl.parse(baseUrl + endpoint).newBuilder();
        params.forEach(urlBuilder::addQueryParameter);
        
        Request request = new Request.Builder()
                .url(urlBuilder.build())
                .get()
                .addHeader("X-BAPI-API-KEY", apiKey)
                .addHeader("X-BAPI-SIGN", signature)
                .addHeader("X-BAPI-TIMESTAMP", String.valueOf(timestamp))
                .addHeader("X-BAPI-RECV-WINDOW", "5000")
                .build();
        
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Niepowodzenie zapytania: " + response.code() + " " + response.message());
            }
            
            String responseBody = response.body().string();
            log.debug("Odpowiedź od API Bybit: {}", responseBody);
            return objectMapper.readTree(responseBody);
        }
    }

    private JsonNode executePostRequest(String endpoint, TreeMap<String, String> params) throws IOException {
        long timestamp = Instant.now().toEpochMilli();
        String paramsJson = objectMapper.writeValueAsString(params);
        
        String signature = generateSignature(timestamp, paramsJson);
        
        RequestBody body = RequestBody.create(
            MediaType.parse("application/json"), 
            paramsJson
        );
        
        Request request = new Request.Builder()
                .url(baseUrl + endpoint)
                .post(body)
                .addHeader("Content-Type", "application/json")
                .addHeader("X-BAPI-API-KEY", apiKey)
                .addHeader("X-BAPI-SIGN", signature)
                .addHeader("X-BAPI-TIMESTAMP", String.valueOf(timestamp))
                .addHeader("X-BAPI-RECV-WINDOW", "5000")
                .build();
        
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Niepowodzenie zapytania: " + response.code() + " " + response.message());
            }
            
            String responseBody = response.body().string();
            log.info("Odpowiedź od API Bybit: {}", responseBody);
            return objectMapper.readTree(responseBody);
        }
    }
} 