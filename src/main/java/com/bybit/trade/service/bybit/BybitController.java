package com.bybit.trade.service.bybit;

import com.bybit.trade.service.bybit.dto.OpenPositionRequestDto;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import java.io.IOException;

/**
 * Kontroler udostępniający endpoints do integracji z Bybit
 */
@Slf4j
@RestController
@RequestMapping("/api/bybit")
@RequiredArgsConstructor
public class BybitController {

    private final BybitIntegrationService bybitIntegrationService;
    private final BybitApiClient bybitApiClient;

    /**
     * Pobiera informacje o otwartych pozycjach z Bybit
     * @return otwarte pozycje w formacie JSON
     */
    @GetMapping("/positions/open")
    public ResponseEntity<JsonNode> getOpenPositions() {
        log.info("Otrzymano żądanie pobrania otwartych pozycji z Bybit");
        JsonNode positions = bybitIntegrationService.getOpenPositions();
        log.info("Zwracam informacje o otwartych pozycjach z Bybit");
        return ResponseEntity.ok(positions);
    }

    /**
     * Pobiera informacje o saldzie konta z Bybit
     * @return saldo konta w formacie JSON
     */
    @GetMapping("/account/balance")
    public ResponseEntity<JsonNode> getAccountBalance() {
        log.info("Otrzymano żądanie pobrania salda konta z Bybit");
        JsonNode balance = bybitIntegrationService.getAccountBalance();
        log.info("Zwracam informacje o saldzie konta z Bybit");
        return ResponseEntity.ok(balance);
    }

    /**
     * Pobiera informacje o pozycjach dla konkretnego symbolu z Bybit
     * @param symbol symbol, dla którego chcemy pobrać pozycje (np. "BTCUSDT")
     * @return pozycje dla danego symbolu w formacie JSON
     */
    @GetMapping("/positions/symbol/{symbol}")
    public ResponseEntity<JsonNode> getPositionsBySymbol(@PathVariable String symbol) {
        log.info("Otrzymano żądanie pobrania pozycji dla symbolu {} z Bybit", symbol);
        JsonNode positions = bybitIntegrationService.getPositionsBySymbol(symbol);
        log.info("Zwracam informacje o pozycjach dla symbolu {} z Bybit", symbol);
        return ResponseEntity.ok(positions);
    }
    
    /**
     * Pobiera aktywne zlecenia dla określonego symbolu.
     *
     * @param symbol symbol handlowy (np. "BTCUSDT")
     * @return informacje o zleceniach w formacie JSON
     */
    @GetMapping("/orders/symbol/{symbol}")
    public ResponseEntity<JsonNode> getOrdersForSymbol(@PathVariable String symbol) {
        try {
            log.info("Otrzymano żądanie pobrania zleceń dla symbolu {} z Bybit", symbol);
            JsonNode response = bybitApiClient.getActiveOrders("linear", symbol);
            log.info("Zwracam informacje o zleceniach dla symbolu {} z Bybit", symbol);
            return ResponseEntity.ok(response);
        } catch (IOException e) {
            log.error("Błąd podczas pobierania zleceń dla symbolu: {}", symbol, e);
            throw new RuntimeException("Nie udało się pobrać zleceń dla symbolu " + symbol + " z Bybit", e);
        }
    }

    /**
     * Otwiera nową pozycję na Bybit
     * @param request dane pozycji do otwarcia
     * @return informacja o otwartej pozycji w formacie JSON
     */
    @PostMapping("/positions/open")
    public ResponseEntity<JsonNode> openPosition(@Valid @RequestBody OpenPositionRequestDto request) {
        log.info("Otrzymano żądanie otwarcia pozycji na Bybit: {}", request);
        JsonNode response = bybitIntegrationService.openPosition(request);
        log.info("Zwracam informacje o otwartej pozycji na Bybit");
        return ResponseEntity.ok(response);
    }
} 