package com.bybit.trade.service;

import com.bybit.trade.model.PositionType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
public class BybitApiService {

    private final RestTemplate restTemplate;

    public BybitApiService() {
        this.restTemplate = new RestTemplate();
        log.info("Inicjalizacja serwisu BybitApiService");
    }

    public BigDecimal openPosition(
            String apiKey,
            String secretKey,
            String symbol,
            PositionType type,
            BigDecimal amount,
            BigDecimal takeProfitPrice,
            BigDecimal stopLossPrice
    ) {
        log.info("Otwieranie pozycji: symbol={}, typ={}, ilość={}", symbol, type, amount);
        log.debug("Take profit: {}, Stop loss: {}", takeProfitPrice, stopLossPrice);
        
        // W rzeczywistej implementacji, tutaj byłaby integracja z API Bybit
        // Poniższy kod jest tylko symulacją
        
        // Przygotowanie danych do wysłania do API
        Map<String, Object> request = prepareOrderRequest(
                apiKey, secretKey, symbol, type, amount, takeProfitPrice, stopLossPrice
        );
        
        log.debug("Przygotowano zapytanie do API: {}", request);
        
        // Tutaj byłoby faktyczne wywołanie API
        // Dla celów demonstracyjnych zwracamy jedynie symulowaną cenę wejścia
        BigDecimal simulatedPrice = getSimulatedPrice(symbol);
        log.info("Symulowana cena wejścia dla {}: {}", symbol, simulatedPrice);
        
        return simulatedPrice;
    }

    public BigDecimal closePosition(
            String apiKey,
            String secretKey,
            String symbol,
            PositionType type,
            BigDecimal amount
    ) {
        log.info("Zamykanie pozycji: symbol={}, typ={}, ilość={}", symbol, type, amount);
        
        // W rzeczywistej implementacji, tutaj byłaby integracja z API Bybit
        // Poniższy kod jest tylko symulacją
        
        // Przygotowanie danych do wysłania do API
        PositionType oppositeType = (type == PositionType.LONG) ? PositionType.SHORT : PositionType.LONG;
        log.debug("Określony przeciwny typ pozycji do zamknięcia: {}", oppositeType);
        
        Map<String, Object> request = prepareOrderRequest(
                apiKey, secretKey, symbol, oppositeType, amount, null, null
        );
        
        log.debug("Przygotowano zapytanie do API: {}", request);
        
        // Tutaj byłoby faktyczne wywołanie API
        // Dla celów demonstracyjnych zwracamy jedynie symulowaną cenę wyjścia
        BigDecimal simulatedPrice = getSimulatedPrice(symbol);
        log.info("Symulowana cena wyjścia dla {}: {}", symbol, simulatedPrice);
        
        return simulatedPrice;
    }

    private Map<String, Object> prepareOrderRequest(
            String apiKey,
            String secretKey,
            String symbol,
            PositionType type,
            BigDecimal amount,
            BigDecimal takeProfitPrice,
            BigDecimal stopLossPrice
    ) {
        log.debug("Przygotowywanie parametrów zapytania dla: symbol={}, typ={}", symbol, type);
        
        Map<String, Object> request = new HashMap<>();
        request.put("symbol", symbol);
        request.put("side", type == PositionType.LONG ? "Buy" : "Sell");
        request.put("orderType", "Market");
        request.put("qty", amount.toString());
        
        if (takeProfitPrice != null) {
            request.put("takeProfit", takeProfitPrice.toString());
            log.debug("Dodano take profit: {}", takeProfitPrice);
        }
        
        if (stopLossPrice != null) {
            request.put("stopLoss", stopLossPrice.toString());
            log.debug("Dodano stop loss: {}", stopLossPrice);
        }
        
        // Dodanie nagłówków autoryzacyjnych
        request.put("apiKey", apiKey);
        // W rzeczywistej implementacji, tutaj byłoby generowanie sygnatury
        request.put("signature", generateSignature(secretKey, request));
        
        return request;
    }
    
    private String generateSignature(String secretKey, Map<String, Object> parameters) {
        // W rzeczywistej implementacji, tutaj byłaby logika generowania sygnatury
        // Dla celów demonstracyjnych zwracamy pusty string
        log.debug("Generowanie sygnatury dla zapytania z parametrami: {}", parameters.keySet());
        return "simulated_signature";
    }
    
    private BigDecimal getSimulatedPrice(String symbol) {
        log.debug("Pobieranie symulowanej ceny dla: {}", symbol);
        
        // Symulowana cena dla różnych symboli
        Map<String, BigDecimal> prices = new HashMap<>();
        prices.put("BTCUSDT", new BigDecimal("36500.50"));
        prices.put("ETHUSDT", new BigDecimal("2250.75"));
        prices.put("SOLUSDT", new BigDecimal("145.25"));
        
        // Dodanie małej losowej zmiany dla symulacji ruchu ceny
        BigDecimal basePrice = prices.getOrDefault(symbol, new BigDecimal("100.00"));
        double randomFactor = 0.99 + Math.random() * 0.02; // Losowa wartość między 0.99 a 1.01
        
        BigDecimal finalPrice = basePrice.multiply(new BigDecimal(randomFactor)).setScale(2, RoundingMode.HALF_UP);
        log.debug("Wygenerowano symulowaną cenę: {} dla {}", finalPrice, symbol);
        
        return finalPrice;
    }
} 