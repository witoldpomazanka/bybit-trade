package com.bybit.trade.controller;

import com.bybit.trade.model.dto.PositionRequestDto;
import com.bybit.trade.model.dto.PositionResponseDto;
import com.bybit.trade.service.PositionService;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/positions")
public class PositionController {

    @Autowired
    private PositionService positionService;

    @PostMapping
    public ResponseEntity<PositionResponseDto> openPosition(@Valid @RequestBody PositionRequestDto positionRequest) {
        log.info("Otrzymano żądanie otwarcia pozycji: {}", positionRequest);
        try {
            PositionResponseDto response = positionService.openPosition(positionRequest);
            log.info("Pozycja otwarta pomyślnie: {}", response);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Błąd podczas otwierania pozycji: {}", e.getMessage(), e);
            return ResponseEntity.badRequest().build();
        }
    }

    @PostMapping("/{id}/close")
    public ResponseEntity<PositionResponseDto> closePosition(@PathVariable("id") Long positionId) {
        log.info("Otrzymano żądanie zamknięcia pozycji o ID: {}", positionId);
        try {
            PositionResponseDto response = positionService.closePosition(positionId);
            log.info("Pozycja zamknięta pomyślnie: {}", response);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Błąd podczas zamykania pozycji o ID {}: {}", positionId, e.getMessage(), e);
            return ResponseEntity.badRequest().build();
        }
    }

    @GetMapping
    public ResponseEntity<List<PositionResponseDto>> getAllPositions() {
        log.info("Otrzymano żądanie pobrania wszystkich pozycji");
        List<PositionResponseDto> positions = positionService.getAllPositions();
        log.info("Pobrano {} pozycji", positions.size());
        return ResponseEntity.ok(positions);
    }

    @GetMapping("/open")
    public ResponseEntity<List<PositionResponseDto>> getOpenPositions() {
        log.info("Otrzymano żądanie pobrania otwartych pozycji");
        List<PositionResponseDto> positions = positionService.getOpenPositions();
        log.info("Pobrano {} otwartych pozycji", positions.size());
        return ResponseEntity.ok(positions);
    }
} 