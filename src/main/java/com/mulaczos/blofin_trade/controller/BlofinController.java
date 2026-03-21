package com.mulaczos.blofin_trade.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.TextNode;
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
}
