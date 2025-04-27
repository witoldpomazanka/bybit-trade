package com.bybit.trade.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.bybit.trade.service.bybit.BybitIntegrationService;
import com.bybit.trade.dto.OpenPositionRequestDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jakarta.validation.Valid;

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

    @PostMapping("/positions/open")
    public ResponseEntity<JsonNode> openPosition(@Valid @RequestBody OpenPositionRequestDto request) {
        log.info("Otrzymano żądanie otwarcia pozycji: {}", request);
        JsonNode result = bybitIntegrationService.openPosition(request);
        log.info("Otwarto pozycję: {}", result);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/account/balance")
    public ResponseEntity<JsonNode> getAccountBalance() {
        log.info("Otrzymano żądanie pobrania salda konta");
        JsonNode result = bybitIntegrationService.getAccountBalance();
        log.info("Pobrano saldo konta: {}", result);
        return ResponseEntity.ok(result);
    }
} 