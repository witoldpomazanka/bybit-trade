package com.bybit.trade.service.bybit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
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

/**
 * Klient API dla Bybit
 * Implementuje podstawowe operacje komunikacji z API Bybit
 */
@Slf4j
@Service
public class BybitApiClient {

    private static final String POSITIONS_ENDPOINT = "/v5/position/list";
    private static final String WALLET_BALANCE_ENDPOINT = "/v5/account/wallet-balance";
    private static final String ORDERS_ENDPOINT = "/v5/order/realtime";
    private static final String PLACE_ORDER_ENDPOINT = "/v5/order/create";
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

    /**
     * Pobiera informacje o pozycjach z Bybit
     * 
     * @param category kategoria instrumentu (np. "linear")
     * @param settleCoin waluta rozliczeniowa (np. "USDT")
     * @return informacje o pozycjach w formacie JSON
     * @throws IOException jeśli wystąpi błąd podczas komunikacji z API
     */
    public JsonNode getPositions(String category, String settleCoin) throws IOException {
        // Parametry zapytania
        TreeMap<String, String> params = new TreeMap<>();
        params.put("category", category);
        params.put("settleCoin", settleCoin);
        
        // Wykonaj zapytanie GET
        return executeGetRequest(POSITIONS_ENDPOINT, params);
    }

    /**
     * Pobiera informacje o saldzie portfela z Bybit
     * 
     * @param accountType typ konta (np. "UNIFIED")
     * @return informacje o saldzie w formacie JSON
     * @throws IOException jeśli wystąpi błąd podczas komunikacji z API
     */
    public JsonNode getWalletBalance(String accountType) throws IOException {
        // Parametry zapytania
        TreeMap<String, String> params = new TreeMap<>();
        params.put("accountType", accountType);
        
        // Wykonaj zapytanie GET
        return executeGetRequest(WALLET_BALANCE_ENDPOINT, params);
    }

    /**
     * Pobiera informacje o pozycjach dla konkretnego symbolu z Bybit
     * 
     * @param category kategoria instrumentu (np. "linear")
     * @param symbol symbol instrumentu (np. "BTCUSDT")
     * @return informacje o pozycjach w formacie JSON
     * @throws IOException jeśli wystąpi błąd podczas komunikacji z API
     */
    public JsonNode getPositionsBySymbol(String category, String symbol) throws IOException {
        // Parametry zapytania
        TreeMap<String, String> params = new TreeMap<>();
        params.put("category", category);
        params.put("symbol", symbol);
        
        // Wykonaj zapytanie GET
        return executeGetRequest(POSITIONS_ENDPOINT, params);
    }

    /**
     * Otwiera nową pozycję na Bybit
     *
     * @param category kategoria instrumentu (np. "linear")
     * @param symbol symbol instrumentu (np. "BTCUSDT")
     * @param side strona transakcji ("Buy" dla long, "Sell" dla short)
     * @param orderType typ zlecenia (np. "Market", "Limit")
     * @param qty ilość
     * @param price cena (opcjonalna dla zleceń typu Market)
     * @param takeProfit cena take profit
     * @param stopLoss cena stop loss
     * @return odpowiedź z API zawierająca informacje o złożonym zleceniu
     * @throws IOException jeśli wystąpi błąd podczas komunikacji z API
     */
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
        
        // Dodatkowe parametry
        params.put("timeInForce", "GoodTillCancel");
        params.put("positionIdx", "0"); // Jedno kierunkowy tryb pozycji
        
        log.info("Otwieranie pozycji: symbol={}, strona={}, ilość={}", symbol, side, qty);
        return executePostRequest(PLACE_ORDER_ENDPOINT, params);
    }

    /**
     * Pobiera aktywne zlecenia dla określonej kategorii i symbolu.
     *
     * @param category kategoria (np. "linear")
     * @param symbol symbol handlowy (np. "BTCUSDT")
     * @return informacje o zleceniach w formacie JSON
     * @throws IOException w przypadku błędu komunikacji z API
     */
    public JsonNode getActiveOrders(String category, String symbol) throws IOException {
        TreeMap<String, String> params = new TreeMap<>();
        params.put("category", category);
        params.put("symbol", symbol);
        
        return executeGetRequest(ORDERS_ENDPOINT, params);
    }

    /**
     * Wykonuje zapytanie GET do API Bybit
     * 
     * @param endpoint endpoint API
     * @param params parametry zapytania
     * @return odpowiedź w formacie JSON
     * @throws IOException jeśli wystąpi błąd podczas komunikacji z API
     */
    private JsonNode executeGetRequest(String endpoint, TreeMap<String, String> params) throws IOException {
        long timestamp = Instant.now().toEpochMilli();
        String queryString = buildQueryString(params);
        
        // Oblicz podpis
        String signature = generateSignature(timestamp, queryString);
        
        // Budowa URL z parametrami
        HttpUrl.Builder urlBuilder = HttpUrl.parse(baseUrl + endpoint).newBuilder();
        params.forEach(urlBuilder::addQueryParameter);
        
        // Budowa zapytania z nagłówkami
        Request request = new Request.Builder()
                .url(urlBuilder.build())
                .get()
                .addHeader("X-BAPI-API-KEY", apiKey)
                .addHeader("X-BAPI-SIGN", signature)
                .addHeader("X-BAPI-TIMESTAMP", String.valueOf(timestamp))
                .addHeader("X-BAPI-RECV-WINDOW", "5000")
                .build();
        
        // Wykonanie zapytania
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Niepowodzenie zapytania: " + response.code() + " " + response.message());
            }
            
            // Parsowanie odpowiedzi
            String responseBody = response.body().string();
            log.debug("Odpowiedź od API Bybit: {}", responseBody);
            return objectMapper.readTree(responseBody);
        }
    }

    /**
     * Wykonuje zapytanie POST do API Bybit
     * 
     * @param endpoint endpoint API
     * @param params parametry zapytania
     * @return odpowiedź w formacie JSON
     * @throws IOException jeśli wystąpi błąd podczas komunikacji z API
     */
    private JsonNode executePostRequest(String endpoint, TreeMap<String, String> params) throws IOException {
        long timestamp = Instant.now().toEpochMilli();
        String paramsJson = objectMapper.writeValueAsString(params);
        
        // Oblicz podpis
        String signature = generateSignature(timestamp, paramsJson);
        
        // Przygotowanie body
        RequestBody body = RequestBody.create(
            MediaType.parse("application/json"), 
            paramsJson
        );
        
        // Budowa zapytania z nagłówkami
        Request request = new Request.Builder()
                .url(baseUrl + endpoint)
                .post(body)
                .addHeader("Content-Type", "application/json")
                .addHeader("X-BAPI-API-KEY", apiKey)
                .addHeader("X-BAPI-SIGN", signature)
                .addHeader("X-BAPI-TIMESTAMP", String.valueOf(timestamp))
                .addHeader("X-BAPI-RECV-WINDOW", "5000")
                .build();
        
        // Wykonanie zapytania
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Niepowodzenie zapytania: " + response.code() + " " + response.message());
            }
            
            // Parsowanie odpowiedzi
            String responseBody = response.body().string();
            log.info("Odpowiedź od API Bybit przy otwieraniu pozycji: {}", responseBody);
            return objectMapper.readTree(responseBody);
        }
    }

    /**
     * Buduje string zapytania z parametrów
     * 
     * @param params parametry zapytania
     * @return string zapytania
     */
    private String buildQueryString(TreeMap<String, String> params) {
        StringBuilder sb = new StringBuilder();
        
        params.forEach((key, value) -> {
            if (sb.length() > 0) {
                sb.append("&");
            }
            sb.append(key).append("=").append(value);
        });
        
        return sb.toString();
    }

    /**
     * Generuje podpis dla zapytania API Bybit
     * 
     * @param timestamp znacznik czasu w milisekundach
     * @param queryString string zapytania
     * @return podpis HMAC-SHA256 w formie heksadecymalnej
     */
    private String generateSignature(long timestamp, String queryString) {
        try {
            String payload = timestamp + apiKey + "5000" + queryString;
            Mac hmacSha256 = Mac.getInstance(HMAC_SHA256);
            SecretKeySpec secretKeySpec = new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), HMAC_SHA256);
            hmacSha256.init(secretKeySpec);
            return Hex.encodeHexString(hmacSha256.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            log.error("Błąd podczas generowania podpisu", e);
            throw new RuntimeException("Nie można wygenerować podpisu dla zapytania API", e);
        }
    }
} 