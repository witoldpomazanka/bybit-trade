package com.bybit.trade.service.bybit;

import com.bybit.trade.service.bybit.dto.OpenPositionRequestDto;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Serwis do integracji z Bybit API
 * Umożliwia pobieranie danych o otwartych pozycjach i saldzie konta
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BybitIntegrationService {

    private static final double PRICE_OFFSET_PERCENTAGE = 0.01; // 0.01% offset dla Post-Only Limit
    private final BybitApiClient bybitApiClient;

    /**
     * Pobiera otwarte pozycje z konta Bybit
     * @return informacja o otwartych pozycjach w formacie JSON
     */
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

    /**
     * Pobiera informacje o saldzie konta Bybit
     * @return informacja o saldzie konta w formacie JSON
     */
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

    /**
     * Pobiera pozycje dla konkretnego symbolu z Bybit
     * @param symbol symbol instrumentu (np. "BTCUSDT")
     * @return informacja o pozycjach dla danego symbolu w formacie JSON
     */
    public JsonNode getPositionsBySymbol(String symbol) {
        try {
            log.info("Pobieranie pozycji dla symbolu {} z Bybit", symbol);
            JsonNode result = bybitApiClient.getPositionsBySymbol("linear", symbol);
            log.info("Pobrano dane o pozycjach dla symbolu {}: {}", symbol, result);
            return result;
        } catch (IOException e) {
            log.error("Błąd podczas pobierania pozycji dla symbolu {} z Bybit", symbol, e);
            throw new RuntimeException("Nie udało się pobrać pozycji dla symbolu " + symbol + " z Bybit", e);
        }
    }

    /**
     * Otwiera nową pozycję na Bybit używając Post-Only Limit lub Market order
     * @param request dane pozycji do otwarcia
     * @return informacja o otwartej pozycji w formacie JSON
     */
    public JsonNode openPosition(OpenPositionRequestDto request) {
        try {
            log.info("Otwieranie pozycji na Bybit: {}", request);
            
            // Określ stronę transakcji na podstawie typu pozycji
            String side = request.getPositionType().equals("LONG") ? "Buy" : "Sell";
            String orderType = request.isUsePostOnlyLimit() ? "Limit" : "Market";
            
            // Konwertuj wielkość pozycji na String
            String qty = String.valueOf(request.getQty());
            
            // Konwertuj take profit i stop loss na String jeśli są ustawione
            String takeProfit = request.getTakeProfit() != null ? 
                String.valueOf(request.getTakeProfit()) : null;
            String stopLoss = request.getStopLoss() != null ? 
                String.valueOf(request.getStopLoss()) : null;

            JsonNode result;
            if (request.isUsePostOnlyLimit()) {
                // Pobierz aktualną cenę rynkową i ustaw cenę limit z małym offsetem
                double currentPrice = getCurrentPrice(request.getSymbol());
                String limitPrice = calculateLimitPrice(currentPrice, side);
                
                log.info("Otwieranie pozycji Post-Only Limit: symbol={}, strona={}, typ={}, cena={}", 
                    request.getSymbol(), side, orderType, limitPrice);
                
                result = bybitApiClient.openPosition(
                    "linear",
                    request.getSymbol(),
                    side,
                    orderType,
                    qty,
                    limitPrice,
                    takeProfit,
                    stopLoss
                );
            } else {
                log.info("Otwieranie pozycji Market: symbol={}, strona={}, typ={}", 
                    request.getSymbol(), side, orderType);
                
                result = bybitApiClient.openPosition(
                    "linear",
                    request.getSymbol(),
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

    /**
     * Pobiera aktualną cenę rynkową dla danego symbolu
     */
    private double getCurrentPrice(String symbol) throws IOException {
        log.info("Pobieranie aktualnej ceny rynkowej dla {}", symbol);
        double price = bybitApiClient.getMarketPrice("linear", symbol);
        log.info("Pobrano aktualną cenę rynkową dla {}: {}", symbol, price);
        return price;
    }

    /**
     * Oblicza cenę dla zlecenia Post-Only Limit
     * Dla LONG: cena limit jest ustawiana nieco poniżej ceny rynkowej
     * Dla SHORT: cena limit jest ustawiana nieco powyżej ceny rynkowej
     */
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