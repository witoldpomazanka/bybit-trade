package com.mulaczos.bybit_trade.service;

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
import java.util.Map;

@Slf4j
@Service
public class BybitApiClient {

    private static final String POSITIONS_ENDPOINT = "/v5/position/list";
    private static final String WALLET_BALANCE_ENDPOINT = "/v5/account/wallet-balance";
    private static final String PLACE_ORDER_ENDPOINT = "/v5/order/create";
    private static final String ORDERBOOK_ENDPOINT = "/v5/market/orderbook";
    private static final String TICKERS_ENDPOINT = "/v5/market/tickers";
    private static final String SET_LEVERAGE_ENDPOINT = "/v5/position/set-leverage";
    private static final String INSTRUMENTS_INFO_ENDPOINT = "/v5/market/instruments-info";
    private static final String SET_TRADING_STOP_ENDPOINT = "/v5/position/trading-stop";
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
            log.warn("Nie udało się pobrać ceny z order booka dla symbolu {}, próbuję z tickerów", symbol, e);
        }

        // Jeśli nie udało się pobrać z order booka, próbujemy z tickerów
        try {
            JsonNode tickersResult = executeGetRequest(TICKERS_ENDPOINT, params);
            if (tickersResult.has("result") && tickersResult.get("result").has("list")) {
                JsonNode tickersList = tickersResult.get("result").get("list");
                if (tickersList.isArray() && tickersList.size() > 0) {
                    JsonNode firstTicker = tickersList.get(0);
                    if (firstTicker.has("lastPrice")) {
                        return Double.parseDouble(firstTicker.get("lastPrice").asText());
                    }
                }
            }
            
            // Sprawdzamy kod błędu
            if (tickersResult.has("retCode") && tickersResult.get("retCode").asInt() != 0) {
                int errorCode = tickersResult.get("retCode").asInt();
                String errorMsg = tickersResult.has("retMsg") ? tickersResult.get("retMsg").asText() : "Unknown error";
                log.error("Błąd API dla symbolu {}: kod={}, wiadomość={}", symbol, errorCode, errorMsg);
            }
        } catch (Exception e) {
            log.error("Wyjątek podczas pobierania ceny z tickerów dla symbolu {}", symbol, e);
        }

        throw new IOException("Nie udało się pobrać ceny rynkowej dla symbolu " + symbol);
    }

    public JsonNode getInstrumentsInfo(String category, String symbol) throws IOException {
        TreeMap<String, String> params = new TreeMap<>();
        params.put("category", category);
        if (symbol != null && !symbol.isEmpty()) {
            params.put("symbol", symbol);
        }
        
        log.info("Pobieranie informacji o instrumencie: kategoria={}, symbol={}", category, symbol);
        return executeGetRequest(INSTRUMENTS_INFO_ENDPOINT, params);
    }

    /**
     * Wyszukuje prawidłowy symbol kontraktu dla danego coina
     * @param coin podstawowy coin (np. "PEPE", "BTC", "DOGE")
     * @return pełna nazwa symbolu kontraktu (np. "1000PEPEUSDT", "BTCUSDT")
     * @throws IOException jeśli nie udało się znaleźć symbolu
     */
    public String findCorrectSymbol(String coin) throws IOException {
        String standardSymbol = coin.toUpperCase() + "USDT";
        TreeMap<String, String> params = new TreeMap<>();
        params.put("category", "linear");
        JsonNode instrumentsInfo = executeGetRequest(INSTRUMENTS_INFO_ENDPOINT, params);
        if (instrumentsInfo.has("result") && instrumentsInfo.get("result").has("list")) {
            JsonNode instrumentsList = instrumentsInfo.get("result").get("list");
            if (instrumentsList.isArray() && instrumentsList.size() > 0) {
                for (JsonNode instrument : instrumentsList) {
                    if (instrument.has("symbol") && standardSymbol.equals(instrument.get("symbol").asText())) {
                        return standardSymbol;
                    }
                }
                String coinUpperCase = coin.toUpperCase();
                for (JsonNode instrument : instrumentsList) {
                    if (instrument.has("symbol")) {
                        String symbol = instrument.get("symbol").asText();
                        if (symbol.contains(coinUpperCase) && symbol.endsWith("USDT")) {
                            return symbol;
                        }
                    }
                }
            }
        }
        throw new IOException("Nie znaleziono prawidłowego symbolu kontraktu dla coina: " + coin);
    }
    
    /**
     * Sprawdza, czy dany symbol jest obsługiwany przez Bybit w określonej kategorii
     * @param category kategoria (np. "linear")
     * @param symbol symbol do sprawdzenia
     * @return true jeśli symbol jest obsługiwany, false w przeciwnym przypadku
     */
    public boolean isSymbolSupported(String category, String symbol) {
        try {
            TreeMap<String, String> params = new TreeMap<>();
            params.put("category", category);
            params.put("symbol", symbol);
            
            JsonNode response = executeGetRequest(INSTRUMENTS_INFO_ENDPOINT, params);
            
            if (response.has("result") && response.get("result").has("list")) {
                JsonNode list = response.get("result").get("list");
                return list.isArray() && list.size() > 0;
            }
            
            return false;
        } catch (Exception e) {
            log.warn("Błąd podczas sprawdzania, czy symbol {} jest obsługiwany: {}", symbol, e.getMessage());
            return false;
        }
    }

    /**
     * Ustawia take profit, stop loss lub trailing stop dla pozycji
     * @param params parametry requestu:
     *               - category: linear/inverse
     *               - symbol: np. BTCUSDT
     *               - takeProfit: cena TP
     *               - stopLoss: cena SL
     *               - tpslMode: Full/Partial
     *               - tpSize: ilość kontraktów dla TP (w trybie Partial)
     *               - slSize: ilość kontraktów dla SL (w trybie Partial)
     *               - tpOrderType: Market/Limit
     *               - slOrderType: Market/Limit
     *               - tpLimitPrice: cena limitowa dla TP (jeśli typ Limit)
     *               - slLimitPrice: cena limitowa dla SL (jeśli typ Limit)
     *               - positionIdx: 0 (one-way), 1 (hedge buy), 2 (hedge sell)
     * @throws IOException jeśli wystąpi błąd podczas wywołania API
     */
    public JsonNode setTradingStop(Map<String, Object> params) throws IOException {
        TreeMap<String, String> stringParams = new TreeMap<>();
        // Konwertuj wszystkie parametry na String
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            stringParams.put(entry.getKey(), entry.getValue().toString());
        }
        
        log.info("Ustawianie TP/SL dla pozycji: {}", params);
        return executePostRequest(SET_TRADING_STOP_ENDPOINT, stringParams);
    }

    public JsonNode setTrailingStop(String category, String symbol, String trailingStop) throws IOException {
        TreeMap<String, String> params = new TreeMap<>();
        params.put("category", category);
        params.put("symbol", symbol);
        params.put("trailingStop", trailingStop);
        params.put("positionIdx", "0");
        
        log.info("Ustawianie trailing stop dla symbolu {}: {}%", symbol, trailingStop);
        return executePostRequest(SET_TRADING_STOP_ENDPOINT, params);
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