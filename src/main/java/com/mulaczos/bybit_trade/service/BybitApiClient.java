package com.mulaczos.bybit_trade.service;

import com.fasterxml.jackson.core.JsonProcessingException;
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
import java.util.Map;
import java.util.TreeMap;

@Slf4j
@Service
@RequiredArgsConstructor
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
    private final OkHttpClient httpClient = new OkHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public JsonNode getPositions(String category, String settleCoin, boolean ommitLogginResponse) {
        TreeMap<String, String> params = new TreeMap<>();
        params.put("category", category);
        params.put("settleCoin", settleCoin);
        return executeGetRequest(POSITIONS_ENDPOINT, params, ommitLogginResponse);
    }

    public JsonNode getWalletBalance(String accountType) {
        TreeMap<String, String> params = new TreeMap<>();
        params.put("accountType", accountType);
        return executeGetRequest(WALLET_BALANCE_ENDPOINT, params, false);
    }

    public JsonNode setLeverage(String category, String symbol, String leverage) {
        TreeMap<String, String> params = new TreeMap<>();
        params.put("category", category);
        params.put("symbol", symbol);
        params.put("buyLeverage", leverage);
        params.put("sellLeverage", leverage);

        log.info("Ustawianie dźwigni dla symbolu {}: {}x", symbol, leverage);
        return executePostRequest(SET_LEVERAGE_ENDPOINT, params);
    }

    public JsonNode openPosition(String category, String symbol, String side, String orderType,
                                 String qty, String price, String takeProfit, String stopLoss) {
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

    public double getMarketPrice(String category, String symbol) {
        TreeMap<String, String> params = new TreeMap<>();
        params.put("category", category);
        params.put("symbol", symbol);

        JsonNode orderBookResult = executeGetRequest(ORDERBOOK_ENDPOINT, params, true);
        if (orderBookResult.has("result") && orderBookResult.get("result").has("a") && orderBookResult.get("result").has("b")) {
            double bestAsk = Double.parseDouble(orderBookResult.get("result").get("a").get(0).get(0).asText());
            double bestBid = Double.parseDouble(orderBookResult.get("result").get("b").get(0).get(0).asText());
            return (bestAsk + bestBid) / 2;
        }

        JsonNode tickersResult = executeGetRequest(TICKERS_ENDPOINT, params, false);
        if (tickersResult.has("result") && tickersResult.get("result").has("list")) {
            JsonNode tickersList = tickersResult.get("result").get("list");
            if (tickersList.isArray() && tickersList.size() > 0) {
                JsonNode firstTicker = tickersList.get(0);
                if (firstTicker.has("lastPrice")) {
                    return Double.parseDouble(firstTicker.get("lastPrice").asText());
                }
            }
        }
        throw new RuntimeException("Problem z pobraniem ceny");
    }

    public JsonNode getInstrumentsInfo(String category, String symbol) {
        TreeMap<String, String> params = new TreeMap<>();
        params.put("category", category);
        if (symbol != null && !symbol.isEmpty()) {
            params.put("symbol", symbol);
        }

        log.info("Pobieranie informacji o instrumencie: kategoria={}, symbol={}", category, symbol);
        return executeGetRequest(INSTRUMENTS_INFO_ENDPOINT, params, true);
    }

    public String findCorrectSymbol(String coin) {
        String standardSymbol = coin.toUpperCase() + "USDT";
        TreeMap<String, String> params = new TreeMap<>();
        params.put("category", "linear");
        
        String nextCursor = null;
        do {
            if (nextCursor != null) {
                params.put("cursor", nextCursor);
            }
            
            JsonNode instrumentsInfo = executeGetRequest(INSTRUMENTS_INFO_ENDPOINT, params, true);
            if (instrumentsInfo.has("result") && instrumentsInfo.get("result").has("list")) {
                JsonNode instrumentsList = instrumentsInfo.get("result").get("list");
                if (instrumentsList.isArray() && instrumentsList.size() > 0) {
                    // Najpierw szukamy dokładnego dopasowania
                    for (JsonNode instrument : instrumentsList) {
                        if (instrument.has("symbol") && standardSymbol.equals(instrument.get("symbol").asText())) {
                            return standardSymbol;
                        }
                    }
                    
                    // Jeśli nie znaleziono dokładnego dopasowania, szukamy częściowego
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
            
            // Sprawdź czy jest następna strona
            nextCursor = instrumentsInfo.has("result") && instrumentsInfo.get("result").has("nextPageCursor") 
                ? instrumentsInfo.get("result").get("nextPageCursor").asText() 
                : null;
                
            // Jeśli nextCursor jest pusty lub "null", zakończ pętlę
            if (nextCursor == null || nextCursor.isEmpty() || "null".equals(nextCursor)) {
                break;
            }
            
        } while (true);
        
        throw new RuntimeException("Nie znaleziono symbolu dla: %s".formatted(coin));
    }

    public boolean isSymbolSupported(String category, String symbol) {
        try {
            TreeMap<String, String> params = new TreeMap<>();
            params.put("category", category);
            params.put("symbol", symbol);

            JsonNode response = executeGetRequest(INSTRUMENTS_INFO_ENDPOINT, params, true);

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

    public JsonNode setTradingStop(Map<String, Object> params) {
        TreeMap<String, String> stringParams = new TreeMap<>();
        // Konwertuj wszystkie parametry na String
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            stringParams.put(entry.getKey(), entry.getValue().toString());
        }

        log.info("Ustawianie TP/SL dla pozycji: {}", params);
        return executePostRequest(SET_TRADING_STOP_ENDPOINT, stringParams);
    }

    public JsonNode setTrailingStop(String category, String symbol, String trailingStop) {
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

    private void validateApiResponse(JsonNode response) {
        if (response.has("retCode")) {
            int retCode = response.get("retCode").asInt();
            String retMsg = response.has("retMsg") ? response.get("retMsg").asText() : "Unknown error";

            if (retCode == 110043) {
                log.warn("Błąd API: Nie zmodyfikowano dźwigni");
                return;
            }

            if (retCode != 0) {
                throw new RuntimeException("BŁĄD API BYBIT: " + retMsg + " (kod: " + retCode + ")");
            }
        }
    }

    private JsonNode executeGetRequest(String endpoint, TreeMap<String, String> params, boolean omitLoggingResponse) {
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
            if (!omitLoggingResponse) {
                log.info("Odpowiedź od API Bybit: {}", responseBody);
            }
            JsonNode result = objectMapper.readTree(responseBody);
            validateApiResponse(result);
            return result;
        } catch (IOException e) {
            log.error("Błąd wykonywania requestu: {}", e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }

    private JsonNode executePostRequest(String endpoint, TreeMap<String, String> params) {
        long timestamp = Instant.now().toEpochMilli();
        String paramsJson = writeValuesAsString(params);

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

        log.debug("Sending request {}", request);
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Niepowodzenie zapytania: " + response.code() + " " + response.message());
            }

            String responseBody = response.body().string();
            log.info("Odpowiedź od API Bybit: {}", responseBody);
            JsonNode result = objectMapper.readTree(responseBody);
            validateApiResponse(result);
            return result;
        } catch (IOException e) {
            log.error("Błąd wykonywania requestu: {}", e.getMessage(), e);
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