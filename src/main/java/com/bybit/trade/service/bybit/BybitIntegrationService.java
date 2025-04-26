package com.bybit.trade.service.bybit;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;

/**
 * Serwis do integracji z Bybit API
 * Umożliwia pobieranie danych o otwartych pozycjach i saldzie konta
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BybitIntegrationService {

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
} 