package com.mulaczos.blofin_trade.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.codec.binary.Hex;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

@Slf4j
@Service
public class BlofinWebSocketClient {

    private final String apiKey;
    private final String secretKey;
    private final String passphrase;
    private final String wssUrl;
    private final ObjectMapper objectMapper;
    private final OkHttpClient httpClient;
    private final ScheduledExecutorService scheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();

    private WebSocket webSocket;
    @Setter
    private Consumer<JsonNode> messageHandler;

    public BlofinWebSocketClient(
            @Value("${blofin.api.key}") String apiKey,
            @Value("${blofin.api.secret}") String secretKey,
            @Value("${blofin.api.passphrase}") String passphrase,
            @Value("${blofin.wss.url:wss://openapi.blofin.com/ws/public}") String wssUrl) {
        this.apiKey = apiKey;
        this.secretKey = secretKey;
        this.passphrase = passphrase;
        this.wssUrl = wssUrl;
        this.objectMapper = new ObjectMapper();
        this.httpClient = new OkHttpClient.Builder()
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .build();
    }

    @PostConstruct
    public void connect() {
        Request request = new Request.Builder().url(wssUrl).build();
        webSocket = httpClient.newWebSocket(request, new BlofinWebSocketListener());
    }

    private class BlofinWebSocketListener extends WebSocketListener {
        @Override
        public void onOpen(WebSocket webSocket, Response response) {
            log.info("Połączono z BloFin WebSocket");
            authenticate();
            startHeartbeat();
        }

        @Override
        public void onMessage(WebSocket webSocket, String text) {
            if ("pong".equals(text)) {
                log.debug("Otrzymano pong od BloFin WebSocket");
                return;
            }
            try {
                JsonNode node = objectMapper.readTree(text);
                if (node.has("event") && "login".equals(node.get("event").asText())) {
                    log.info("Zalogowano do WebSocket BloFin");
                    subscribeToOrders();
                } else if (messageHandler != null) {
                    messageHandler.accept(node);
                }
            } catch (Exception e) {
                log.error("Błąd przetwarzania wiadomości WS: {}", text, e);
            }
        }

        @Override
        public void onClosing(WebSocket webSocket, int code, String reason) {
            log.warn("Zamykanie połączenia WebSocket: {} / {}", code, reason);
        }

        @Override
        public void onFailure(WebSocket webSocket, Throwable t, Response response) {
            log.error("Błąd WebSocket: {}", t != null ? t.getMessage() : "null");
            // Prosty mechanizm reconnect po 5s
            try {
                TimeUnit.SECONDS.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            connect();
        }
    }

    private void authenticate() {
        long timestamp = Instant.now().getEpochSecond();
        String method = "GET";
        String path = "/user/verify";
        String signStr = timestamp + method + path;

        try {
            Mac sha256HMAC = Mac.getInstance("HmacSHA256");
            SecretKeySpec secretKeySpec = new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            sha256HMAC.init(secretKeySpec);
            byte[] hmacBytes = sha256HMAC.doFinal(signStr.getBytes(StandardCharsets.UTF_8));
            String sign = Base64.encodeBase64String(Hex.encodeHexString(hmacBytes).getBytes(StandardCharsets.UTF_8));

            Map<String, Object> authArgs = new HashMap<>();
            authArgs.put("apiKey", apiKey);
            authArgs.put("passphrase", passphrase);
            authArgs.put("timestamp", String.valueOf(timestamp));
            authArgs.put("sign", sign);

            Map<String, Object> loginMsg = new HashMap<>();
            loginMsg.put("op", "login");
            loginMsg.put("args", Collections.singletonList(authArgs));

            webSocket.send(objectMapper.writeValueAsString(loginMsg));
        } catch (Exception e) {
            log.error("Błąd autentykacji WS", e);
        }
    }

    private void subscribeToOrders() {
        try {
            Map<String, Object> subArgs = new HashMap<>();
            subArgs.put("channel", "orders");

            Map<String, Object> subMsg = new HashMap<>();
            subMsg.put("op", "subscribe");
            subMsg.put("args", Collections.singletonList(subArgs));

            webSocket.send(objectMapper.writeValueAsString(subMsg));
            log.info("Subskrypcja kanału 'orders' wysłana");
        } catch (Exception e) {
            log.error("Błąd subskrypcji WS", e);
        }
    }

    private void startHeartbeat() {
        scheduler.scheduleAtFixedRate(() -> {
            if (webSocket != null) {
                webSocket.send("ping");
            }
        }, 20, 20, TimeUnit.SECONDS);
    }
}
