package com.bybit.trade.service;

import com.bybit.trade.config.ApiConfig;
import com.bybit.trade.model.Position;
import com.bybit.trade.model.PositionStatus;
import com.bybit.trade.model.PositionType;
import com.bybit.trade.model.dto.PositionRequestDto;
import com.bybit.trade.model.dto.PositionResponseDto;
import com.bybit.trade.repository.PositionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
public class PositionService {

    @Autowired
    private PositionRepository positionRepository;

    @Autowired
    private BybitApiService bybitApiService;
    
    @Autowired
    private ApiConfig apiConfig;

    @Transactional
    public PositionResponseDto openPosition(PositionRequestDto positionRequest) {
        log.info("Rozpoczęcie procesu otwierania pozycji dla symbolu: {}, typ: {}, ilość: {}", 
            positionRequest.getSymbol(), positionRequest.getType(), positionRequest.getAmount());
            
        // Pobieramy dane dostępowe z konfiguracji
        String apiKey = apiConfig.getApiKey();
        String secretKey = apiConfig.getSecretKey();
        
        log.debug("Użycie klucza API: {}, dla otwarcia pozycji", apiKey);

        // Wywołujemy serwis Bybit API do otwarcia pozycji
        BigDecimal entryPrice = bybitApiService.openPosition(
                apiKey,
                secretKey,
                positionRequest.getSymbol(),
                positionRequest.getType(),
                positionRequest.getAmount(),
                positionRequest.getTakeProfitPrice(),
                positionRequest.getStopLossPrice()
        );
        
        log.info("Cena wejścia dla pozycji {}: {}", positionRequest.getSymbol(), entryPrice);

        // Zapisujemy pozycję w bazie danych
        Position position = new Position();
        position.setSymbol(positionRequest.getSymbol());
        position.setType(positionRequest.getType());
        position.setAmount(positionRequest.getAmount());
        position.setEntryPrice(entryPrice);
        position.setTakeProfitPrice(positionRequest.getTakeProfitPrice());
        position.setStopLossPrice(positionRequest.getStopLossPrice());
        position.setOpenTime(LocalDateTime.now());
        position.setStatus(PositionStatus.OPEN);

        Position savedPosition = positionRepository.save(position);
        log.info("Pozycja zapisana w bazie danych z ID: {}", savedPosition.getId());

        return convertToDto(savedPosition);
    }

    @Transactional
    public PositionResponseDto closePosition(Long positionId) {
        log.info("Rozpoczęcie procesu zamykania pozycji o ID: {}", positionId);
        
        Position position = positionRepository.findById(positionId)
                .orElseThrow(() -> {
                    log.error("Nie znaleziono pozycji o ID: {}", positionId);
                    return new RuntimeException("Pozycja nie została znaleziona");
                });

        if (position.getStatus() != PositionStatus.OPEN) {
            log.error("Próba zamknięcia pozycji o ID: {}, która nie jest otwarta. Status: {}", 
                positionId, position.getStatus());
            throw new RuntimeException("Pozycja jest już zamknięta");
        }

        // Pobieramy dane dostępowe z konfiguracji
        String apiKey = apiConfig.getApiKey();
        String secretKey = apiConfig.getSecretKey();
        
        log.debug("Użycie klucza API: {}, dla zamknięcia pozycji", apiKey);

        // Wywołujemy serwis Bybit API do zamknięcia pozycji
        BigDecimal closePrice = bybitApiService.closePosition(
                apiKey,
                secretKey,
                position.getSymbol(),
                position.getType(),
                position.getAmount()
        );
        
        log.info("Cena zamknięcia dla pozycji {} (ID: {}): {}", 
            position.getSymbol(), positionId, closePrice);

        // Obliczanie zysku/straty
        BigDecimal profit;
        if (position.getType() == PositionType.LONG) {
            profit = closePrice.subtract(position.getEntryPrice()).multiply(position.getAmount());
        } else {
            profit = position.getEntryPrice().subtract(closePrice).multiply(position.getAmount());
        }
        
        log.info("Zysk/strata dla pozycji {} (ID: {}): {}", 
            position.getSymbol(), positionId, profit);

        position.setClosePrice(closePrice);
        position.setCloseTime(LocalDateTime.now());
        position.setProfit(profit);
        position.setStatus(PositionStatus.CLOSED);

        Position updatedPosition = positionRepository.save(position);
        log.info("Pozycja o ID: {} została pomyślnie zamknięta", positionId);

        return convertToDto(updatedPosition);
    }

    public List<PositionResponseDto> getAllPositions() {
        log.info("Pobieranie wszystkich pozycji");
        List<Position> positions = positionRepository.findAll();
        log.info("Znaleziono {} pozycji", positions.size());
        return positions.stream()
                .map(this::convertToDto)
                .collect(Collectors.toList());
    }

    public List<PositionResponseDto> getOpenPositions() {
        log.info("Pobieranie otwartych pozycji");
        List<Position> positions = positionRepository.findByStatus(PositionStatus.OPEN);
        log.info("Znaleziono {} otwartych pozycji", positions.size());
        return positions.stream()
                .map(this::convertToDto)
                .collect(Collectors.toList());
    }

    private PositionResponseDto convertToDto(Position position) {
        log.debug("Konwertowanie pozycji o ID: {} na DTO", position.getId());
        return new PositionResponseDto(
                position.getId(),
                position.getSymbol(),
                position.getType(),
                position.getAmount(),
                position.getEntryPrice(),
                position.getTakeProfitPrice(),
                position.getStopLossPrice(),
                position.getOpenTime(),
                position.getCloseTime(),
                position.getClosePrice(),
                position.getProfit(),
                position.getStatus()
        );
    }
} 