package com.mulaczos.bybit_trade.controller;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.TextNode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.mulaczos.bybit_trade.service.BybitIntegrationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Map;

import com.mulaczos.bybit_trade.dto.TradingResponseDto;
import com.mulaczos.bybit_trade.dto.ScalpRequestDto;

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

    @GetMapping("/account/balance")
    public ResponseEntity<JsonNode> getAccountBalance() {
        log.info("Otrzymano żądanie pobrania salda konta");
        JsonNode result = bybitIntegrationService.getAccountBalance();
        log.info("Pobrano saldo konta: {}", result);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/positions/market/advanced")
    public ResponseEntity<JsonNode> openAdvancedMarketPosition(@RequestBody Map<String, Object> payload) {
        if (payload.containsKey("content")) {
            return ResponseEntity.ok(JsonNodeFactory.instance.objectNode().set("content", TextNode.valueOf("invalid")));
        }
        JsonNode result = bybitIntegrationService.openAdvancedMarketPosition(payload);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/positions/market/advanced/test")
    public ResponseEntity logRequest(@RequestBody Object payload) {
        log.info("Received request payload: {}", payload);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/positions/scalp")
    public ResponseEntity<TradingResponseDto> openScalpPosition(@RequestBody ScalpRequestDto request) {
        return ResponseEntity.ok(bybitIntegrationService.openScalpShortPosition(request));
    }
} 