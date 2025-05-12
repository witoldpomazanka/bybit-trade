package com.mulaczos.bybit_trade.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.TextNode;
import com.mulaczos.bybit_trade.dto.ScalpRequestDto;
import com.mulaczos.bybit_trade.dto.TradingResponseDto;
import com.mulaczos.bybit_trade.service.BybitIntegrationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/bybit")
public class BybitController {

    private final BybitIntegrationService bybitIntegrationService;

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
    
    @PostMapping("/positions/advanced")
    public ResponseEntity<JsonNode> openAdvancedPosition(
            @RequestBody Map<String, Object> payload,
            @RequestHeader(value = "chat-title", required = false) String chatTitle) {
        if (chatTitle != null) {
            log.info("Chat title: {}", chatTitle);
        }
        if (payload.containsKey("content")) {
            return ResponseEntity.ok(JsonNodeFactory.instance.objectNode().set("content", TextNode.valueOf("invalid")));
        }
        JsonNode result = bybitIntegrationService.openAdvancedPosition(payload, chatTitle);
        log.info("------------------------------------------------------------------------------------------------------------------------");
        return ResponseEntity.ok(result);
    }

    @PostMapping("/positions/scalp")
    public ResponseEntity<TradingResponseDto> openScalpPosition(@RequestBody ScalpRequestDto request) {
        return ResponseEntity.ok(bybitIntegrationService.openScalpShortPosition(request));
    }
} 