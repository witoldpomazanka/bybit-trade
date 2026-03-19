package com.mulaczos.blofin_trade.exception;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Globalny handler wyjątków – zapobiega zwracaniu Whitelabel 500 do n8n.
 * BlofinApiException (błąd biznesowy API) → HTTP 422 z czytelnym JSON-em.
 * Pozostałe RuntimeException → HTTP 500 z JSON-em (zamiast HTML).
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BlofinApiException.class)
    public ResponseEntity<ObjectNode> handleBlofinApiException(BlofinApiException ex) {
        log.error("BloFin API zwrócił błąd biznesowy [{}]: {}", ex.getApiCode(), ex.getApiMsg());
        ObjectNode body = JsonNodeFactory.instance.objectNode();
        body.put("error", true);
        body.put("apiCode", ex.getApiCode());
        body.put("message", ex.getApiMsg());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(body);
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ObjectNode> handleRuntimeException(RuntimeException ex) {
        log.error("Nieoczekiwany błąd systemu: {}", ex.getMessage(), ex);
        ObjectNode body = JsonNodeFactory.instance.objectNode();
        body.put("error", true);
        body.put("message", ex.getMessage());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }
}
