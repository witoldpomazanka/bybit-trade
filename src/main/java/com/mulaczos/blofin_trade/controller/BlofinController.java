package com.mulaczos.blofin_trade.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.TextNode;
import com.mulaczos.blofin_trade.dto.ScalpRequestDto;
import com.mulaczos.blofin_trade.dto.TradingResponseDto;
import com.mulaczos.blofin_trade.service.BlofinIntegrationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/bybit")
public class BlofinController {

    private final BlofinIntegrationService blofinIntegrationService;

    @GetMapping("/positions/open")
    public ResponseEntity<JsonNode> getOpenPositions() {
        log.info("Otrzymano żądanie pobrania otwartych pozycji");
        JsonNode result = blofinIntegrationService.getOpenPositions();
        log.info("Pobrano otwarte pozycje: {}", result);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/account/balance")
    public ResponseEntity<JsonNode> getAccountBalance() {
        log.info("Otrzymano żądanie pobrania salda konta");
        JsonNode result = blofinIntegrationService.getAccountBalance();
        log.info("Pobrano dane o saldzie konta: {}", result);
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
        JsonNode result = blofinIntegrationService.openAdvancedPosition(payload, chatTitle);
        log.info("------------------------------------------------------------------------------------------------------------------------");
        return ResponseEntity.ok(result);
    }

    @PostMapping("/positions/scalp")
    public ResponseEntity<TradingResponseDto> openScalpPosition(@RequestBody ScalpRequestDto request) {
        return ResponseEntity.ok(blofinIntegrationService.openScalpShortPosition(request));
    }
}

