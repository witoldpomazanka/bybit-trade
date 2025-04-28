package com.bybit.trade.service.bybit;

import com.bybit.trade.dto.OpenPositionRequestDto;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;

@Slf4j
@Service
@RequiredArgsConstructor
public class BybitIntegrationService {

    private static final double PRICE_OFFSET_PERCENTAGE = 0.01; // 0.01% offset dla Post-Only Limit
    private static final BigDecimal MIN_NOTIONAL_VALUE = new BigDecimal("5.0"); // Minimalna wartość zamówienia w USDT
    private static final BigDecimal MIN_BTC_QTY = new BigDecimal("0.001"); // Minimalna ilość BTC
    private static final BigDecimal MIN_ETH_QTY = new BigDecimal("0.01"); // Minimalna ilość ETH
    private static final int DEFAULT_LEVERAGE = 10; // Domyślna dźwignia x10
    
    private final BybitApiClient bybitApiClient;

    public JsonNode getOpenPositions() {
        try {
            log.info("Pobieranie otwartych pozycji z Bybit");
            JsonNode result = bybitApiClient.getPositions("linear", "USDT");
            log.info("Pobrano dane o otwartych pozycjach: {}", result);
            return result;
        } catch (IOException e) {
            log.error("Błąd podczas pobierania otwartych pozycji z Bybit", e);
            throw new RuntimeException("Nie udało się pobrać otwartych pozycji z Bybit", e);
        }
    }

    public JsonNode getAccountBalance() {
        try {
            log.info("Pobieranie salda konta z Bybit");
            JsonNode result = bybitApiClient.getWalletBalance("UNIFIED");
            log.info("Pobrano dane o saldzie konta: {}", result);
            return result;
        } catch (IOException e) {
            log.error("Błąd podczas pobierania salda konta z Bybit", e);
            throw new RuntimeException("Nie udało się pobrać salda konta z Bybit", e);
        }
    }
    
    // Otwieranie pozycji LONG market
    public JsonNode openLongMarketPosition(OpenPositionRequestDto request) {
        log.info("Otwieranie długiej pozycji Market na Bybit: {}", request);
        return openPosition(request, true, false);
    }
    
    // Otwieranie pozycji SHORT market
    public JsonNode openShortMarketPosition(OpenPositionRequestDto request) {
        log.info("Otwieranie krótkiej pozycji Market na Bybit: {}", request);
        return openPosition(request, false, false);
    }
    
    // Otwieranie pozycji LONG limit
    public JsonNode openLongLimitPosition(OpenPositionRequestDto request) {
        log.info("Otwieranie długiej pozycji Limit na Bybit: {}", request);
        return openPosition(request, true, true);
    }
    
    // Otwieranie pozycji SHORT limit
    public JsonNode openShortLimitPosition(OpenPositionRequestDto request) {
        log.info("Otwieranie krótkiej pozycji Limit na Bybit: {}", request);
        return openPosition(request, false, true);
    }
    
    // Dla kompatybilności
    public JsonNode openMarketPosition(OpenPositionRequestDto request) {
        log.info("Otwieranie pozycji Market (stara metoda) na Bybit: {}", request);
        return openLongMarketPosition(request);
    }
    
    // Dla kompatybilności
    public JsonNode openLimitPosition(OpenPositionRequestDto request) {
        log.info("Otwieranie pozycji Limit (stara metoda) na Bybit: {}", request);
        return openLongLimitPosition(request);
    }

    private JsonNode openPosition(OpenPositionRequestDto request, boolean isLong, boolean isPostOnlyLimit) {
        try {
            // Budujemy pełny symbol pary walutowej
            String symbol = request.getCoin().toUpperCase() + "USDT";
            log.info("Otwieranie pozycji na Bybit: {} (symbol: {})", request, symbol);
            
            // Ustalenie dźwigni
            int leverage = request.getLeverage() != null ? request.getLeverage() : DEFAULT_LEVERAGE;
            log.info("Używanie dźwigni {}x dla symbolu {}", leverage, symbol);
            
            // Ustawienie dźwigni przed otwarciem pozycji
            JsonNode leverageResult = bybitApiClient.setLeverage("linear", symbol, String.valueOf(leverage));
            log.info("Wynik ustawienia dźwigni: {}", leverageResult);
            
            String side = isLong ? "Buy" : "Sell";
            String orderType = isPostOnlyLimit ? "Limit" : "Market";
            
            // Pobieranie aktualnej ceny rynkowej
            double currentPrice = getCurrentPrice(symbol);
            log.info("Aktualna cena rynkowa dla {}: {}", symbol, currentPrice);
            
            // Obliczanie ilości kontraktów na podstawie kwoty USDT i aktualnej ceny
            BigDecimal price = BigDecimal.valueOf(currentPrice);
            BigDecimal usdtAmount = BigDecimal.valueOf(request.getUsdtAmount());
            
            // Sprawdzenie, czy kwota USDT spełnia minimalne wymagania
            if (usdtAmount.compareTo(MIN_NOTIONAL_VALUE) < 0) {
                log.warn("Kwota USDT {} jest mniejsza niż minimalna wymagana wartość {}. Ustawiam minimalną wartość.", 
                    usdtAmount, MIN_NOTIONAL_VALUE);
                usdtAmount = MIN_NOTIONAL_VALUE;
            }
            
            BigDecimal quantity = usdtAmount.divide(price, 8, RoundingMode.HALF_UP);
            
            // Sprawdzenie minimalnych limitów dla poszczególnych symboli
            BigDecimal minQty;
            if (request.getCoin().toUpperCase().equals("BTC")) {
                minQty = MIN_BTC_QTY;
            } else if (request.getCoin().toUpperCase().equals("ETH")) {
                minQty = MIN_ETH_QTY;
            } else {
                // Dla innych symboli przyjmujemy bezpieczną wartość
                minQty = new BigDecimal("0.01");
            }
            
            if (quantity.compareTo(minQty) < 0) {
                log.warn("Obliczona ilość {} jest mniejsza niż minimalna wymagana ilość {} dla {}. Ustawiam minimalną ilość.", 
                    quantity, minQty, symbol);
                quantity = minQty;
            }
            
            // Zaokrąglenie ilości kontraktów do 5 miejsc po przecinku
            quantity = quantity.setScale(5, RoundingMode.HALF_UP);
            String qty = quantity.toString();
            
            log.info("Przeliczono kwotę {} USDT na {} kontraktów przy cenie {}", 
                     request.getUsdtAmount(), qty, currentPrice);
            
            String takeProfit = request.getTakeProfit() != null ? 
                request.getTakeProfit().toString() : null;
            String stopLoss = request.getStopLoss() != null ? 
                request.getStopLoss().toString() : null;

            JsonNode result;
            if (isPostOnlyLimit) {
                String limitPrice = calculateLimitPrice(currentPrice, side);
                
                log.info("Otwieranie pozycji Post-Only Limit: symbol={}, strona={}, typ={}, ilość={}, cena={}", 
                    symbol, side, orderType, qty, limitPrice);
                
                result = bybitApiClient.openPosition(
                    "linear",
                    symbol,
                    side,
                    orderType,
                    qty,
                    limitPrice,
                    takeProfit,
                    stopLoss
                );
            } else {
                log.info("Otwieranie pozycji Market: symbol={}, strona={}, typ={}, ilość={}", 
                    symbol, side, orderType, qty);
                
                result = bybitApiClient.openPosition(
                    "linear",
                    symbol,
                    side,
                    orderType,
                    qty,
                    null,
                    takeProfit,
                    stopLoss
                );
            }
            
            log.info("Otwarto pozycję na Bybit: {}", result);
            return result;
        } catch (IOException e) {
            log.error("Błąd podczas otwierania pozycji na Bybit: {}", request, e);
            throw new RuntimeException("Nie udało się otworzyć pozycji na Bybit", e);
        }
    }

    private double getCurrentPrice(String symbol) throws IOException {
        log.info("Pobieranie aktualnej ceny rynkowej dla {}", symbol);
        double price = bybitApiClient.getMarketPrice("linear", symbol);
        log.info("Pobrano aktualną cenę rynkową dla {}: {}", symbol, price);
        return price;
    }

    private String calculateLimitPrice(double currentPrice, String side) {
        BigDecimal price = BigDecimal.valueOf(currentPrice);
        BigDecimal offset = price.multiply(BigDecimal.valueOf(PRICE_OFFSET_PERCENTAGE))
            .divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_UP);
        
        if (side.equals("Buy")) {
            price = price.subtract(offset);
        } else {
            price = price.add(offset);
        }
        
        return price.setScale(2, RoundingMode.HALF_UP).toString();
    }
} 