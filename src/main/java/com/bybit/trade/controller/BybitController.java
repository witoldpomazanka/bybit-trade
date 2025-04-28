package com.bybit.trade.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.bybit.trade.service.bybit.BybitIntegrationService;
import com.bybit.trade.dto.OpenPositionRequestDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jakarta.validation.Valid;
import java.util.Map;

@RestController
@RequestMapping("/api/bybit")
public class BybitController {

    private static final Logger log = LoggerFactory.getLogger(BybitController.class);

    private final BybitIntegrationService bybitIntegrationService;

    public BybitController(BybitIntegrationService bybitIntegrationService) {
        this.bybitIntegrationService = bybitIntegrationService;
    }

    @GetMapping("/positions/open")
    public ResponseEntity<JsonNode> getOpenPositions() {
        log.info("Otrzymano żądanie pobrania otwartych pozycji");
        JsonNode result = bybitIntegrationService.getOpenPositions();
        log.info("Pobrano otwarte pozycje: {}", result);
        return ResponseEntity.ok(result);
    }

    /**
     * Endpoint do otwierania długiej pozycji za pomocą zlecenia Market.
     */
    @PostMapping("/positions/long/market")
    public ResponseEntity<JsonNode> openLongMarketPosition(@Valid @RequestBody OpenPositionRequestDto request) {
        log.info("Otrzymano żądanie otwarcia długiej pozycji Market: {}", request);
        JsonNode result = bybitIntegrationService.openLongMarketPosition(request);
        log.info("Otwarto długą pozycję Market: {}", result);
        return ResponseEntity.ok(result);
    }

    /**
     * Endpoint do otwierania krótkiej pozycji za pomocą zlecenia Market.
     */
    @PostMapping("/positions/short/market")
    public ResponseEntity<JsonNode> openShortMarketPosition(@Valid @RequestBody OpenPositionRequestDto request) {
        log.info("Otrzymano żądanie otwarcia krótkiej pozycji Market: {}", request);
        JsonNode result = bybitIntegrationService.openShortMarketPosition(request);
        log.info("Otwarto krótką pozycję Market: {}", result);
        return ResponseEntity.ok(result);
    }

    /**
     * Endpoint do otwierania długiej pozycji za pomocą zlecenia Limit (Post-Only).
     */
    @PostMapping("/positions/long/limit")
    public ResponseEntity<JsonNode> openLongLimitPosition(@Valid @RequestBody OpenPositionRequestDto request) {
        log.info("Otrzymano żądanie otwarcia długiej pozycji Limit (Post-Only): {}", request);
        JsonNode result = bybitIntegrationService.openLongLimitPosition(request);
        log.info("Otwarto długą pozycję Limit (Post-Only): {}", result);
        return ResponseEntity.ok(result);
    }

    /**
     * Endpoint do otwierania krótkiej pozycji za pomocą zlecenia Limit (Post-Only).
     */
    @PostMapping("/positions/short/limit")
    public ResponseEntity<JsonNode> openShortLimitPosition(@Valid @RequestBody OpenPositionRequestDto request) {
        log.info("Otrzymano żądanie otwarcia krótkiej pozycji Limit (Post-Only): {}", request);
        JsonNode result = bybitIntegrationService.openShortLimitPosition(request);
        log.info("Otwarto krótką pozycję Limit (Post-Only): {}", result);
        return ResponseEntity.ok(result);
    }

    /**
     * Zachowujemy kompatybilność wsteczną.
     */
    @PostMapping("/positions/market/open")
    public ResponseEntity<JsonNode> openMarketPosition(@Valid @RequestBody OpenPositionRequestDto request) {
        log.info("Otrzymano żądanie otwarcia pozycji Market (stary endpoint): {}", request);
        return openLongMarketPosition(request);
    }

    /**
     * Zachowujemy kompatybilność wsteczną.
     */
    @PostMapping("/positions/limit/open")
    public ResponseEntity<JsonNode> openLimitPosition(@Valid @RequestBody OpenPositionRequestDto request) {
        log.info("Otrzymano żądanie otwarcia pozycji Limit (stary endpoint): {}", request);
        return openLongLimitPosition(request);
    }

    /**
     * Zachowujemy kompatybilność wsteczną z istniejącym endpointem.
     */
    @PostMapping("/positions/open")
    public ResponseEntity<JsonNode> openPosition(@Valid @RequestBody OpenPositionRequestDto request) {
        log.info("Otrzymano żądanie otwarcia pozycji (najstarszy endpoint): {}", request);
        return openLongLimitPosition(request);
    }

    @GetMapping("/account/balance")
    public ResponseEntity<JsonNode> getAccountBalance() {
        log.info("Otrzymano żądanie pobrania salda konta");
        JsonNode result = bybitIntegrationService.getAccountBalance();
        log.info("Pobrano saldo konta: {}", result);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/positions/market/advanced")
    public ResponseEntity<JsonNode> openAdvancedMarketPosition(@RequestBody Map<String, Object> payload) {
        JsonNode result = bybitIntegrationService.openAdvancedMarketPosition(payload);
        return ResponseEntity.ok(result);
    }
} 