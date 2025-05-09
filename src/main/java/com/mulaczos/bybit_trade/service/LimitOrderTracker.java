package com.mulaczos.bybit_trade.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.mulaczos.bybit_trade.model.LimitOrder;
import com.mulaczos.bybit_trade.model.LimitOrderTakeProfit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class LimitOrderTracker {
    
    private final LimitOrderService limitOrderService;
    private final BybitApiClient bybitApiClient;
    private final BybitIntegrationService bybitIntegrationService;
    private final TwilioNotificationService twilioNotificationService;
    
    @Value("${limit-order.tracker.check-interval:30000}")
    private long checkInterval;
    
    @Scheduled(fixedDelayString = "${limit-order.tracker.check-interval:30000}")
    @Transactional
    public void checkPendingOrders() {
        List<LimitOrder> pendingOrders = limitOrderService.getPendingOrders();
        
        if (pendingOrders.isEmpty()) {
            return;
        }
        
        log.info("Znaleziono {} oczekujących zleceń limit", pendingOrders.size());
        
        for (LimitOrder order : pendingOrders) {
            try {
                checkOrderStatus(order);
            } catch (Exception e) {
                log.error("Błąd podczas sprawdzania zlecenia {}: {}", order.getOrderId(), e.getMessage(), e);
            }
        }
    }
    
    private void checkOrderStatus(LimitOrder order) {
        log.info("Sprawdzanie statusu zlecenia: {}, symbol: {}", order.getOrderId(), order.getSymbol());
        
        // Aktualizacja czasu ostatniego sprawdzenia
        order.setLastCheckedAt(LocalDateTime.now());
        
        // 1. Sprawdź czy pozycja została otwarta
        JsonNode positions = bybitApiClient.getPositions("linear", "USDT");
        boolean positionFound = false;
        
        if (positions.has("result") && positions.get("result").has("list")) {
            JsonNode positionsList = positions.get("result").get("list");
            for (JsonNode position : positionsList) {
                if (position.has("symbol") && order.getSymbol().equals(position.get("symbol").asText()) &&
                        position.has("size") && position.get("size").asDouble() > 0) {
                    
                    // Znaleziono pozycję - zlecenie zostało zrealizowane
                    positionFound = true;
                    log.info("Znaleziono otwartą pozycję dla zlecenia limit: {}", order.getOrderId());
                    
                    // Zaktualizuj status zlecenia
                    order.setStatus("FILLED");
                    order.setFilledAt(LocalDateTime.now());
                    
                    // Skonfiguruj partial take-profits
                    configurePartialTakeProfits(order, position);
                    
                    // Wyślij powiadomienie SMS
                    twilioNotificationService.sendPositionOpenedNotification(
                            order.getSymbol(),
                            order.getSide(),
                            order.getQuantity(),
                            order.getLeverage()
                    );
                    
                    break;
                }
            }
        }
        
        if (!positionFound) {
            // Sprawdź czy zlecenie nie zostało anulowane przez użytkownika
            // To bardziej złożone i wymaga dodatkowych zapytań do API Bybit
            // Można rozważyć implementację w przyszłości
            log.info("Nie znaleziono otwartej pozycji dla zlecenia: {}, symbol: {}", order.getOrderId(), order.getSymbol());
        }
    }
    
    private void configurePartialTakeProfits(LimitOrder order, JsonNode position) {
        try {
            // Pobranie takeProfits z bazy danych
            List<LimitOrderTakeProfit> takeProfits = limitOrderService.getUnprocessedTakeProfitsForOrder(order.getId());
            if (takeProfits.isEmpty()) {
                log.info("Brak nieskonfigurowanych take-profits dla zlecenia: {}", order.getOrderId());
                order.setStatus("PROCESSED_TP_SL");
                return;
            }
            
            // Pobranie wielkości pozycji
            double positionSize = position.get("size").asDouble();
            log.info("Wielkość pozycji do konfiguracji TP: {}", positionSize);
            
            // Pobranie minimalnego limitu dla zamówienia
            BigDecimal minQty = bybitIntegrationService.getMinimumOrderQuantity(order.getSymbol());
            log.info("Minimalny limit dla {}: {}", order.getSymbol(), minQty);
            
            // Obliczenie równych części dla wszystkich TP oprócz ostatniego
            int tpCount = takeProfits.size();
            double basePartSize = Math.floor((positionSize / tpCount) * 100) / 100.0;
            double remainingQty = positionSize;
            log.info("Bazowa wielkość dla każdego TP (oprócz ostatniego): {}, pozostała ilość: {}", basePartSize, remainingQty);
            
            // Konfiguracja dla każdego TP
            for (int i = 0; i < takeProfits.size(); i++) {
                LimitOrderTakeProfit tp = takeProfits.get(i);
                int tpNumber = tp.getPosition();
                boolean isLast = (i == takeProfits.size() - 1);
                
                double tpSize;
                if (isLast) {
                    // Dla ostatniego TP użyj pozostałej ilości
                    tpSize = remainingQty;
                    log.info("Ostatni TP ({}), użycie pozostałej ilości: {}", tpNumber, tpSize);
                } else {
                    tpSize = basePartSize;
                    log.info("TP {}, użycie bazowej wielkości: {}, pozostało: {}", tpNumber, tpSize, remainingQty - basePartSize);
                }
                
                if (BigDecimal.valueOf(tpSize).compareTo(minQty) < 0) {
                    tpSize = minQty.doubleValue();
                    log.info("Wielkość TP skorygowana do minimalnego limitu: {}", tpSize);
                }
                
                Map<String, Object> tpReq = new HashMap<>();
                tpReq.put("category", "linear");
                tpReq.put("symbol", order.getSymbol());
                tpReq.put("tpslMode", "Partial");
                tpReq.put("tpOrderType", "Market");
                tpReq.put("tpSize", String.valueOf(tpSize));
                tpReq.put("takeProfit", tp.getPrice());
                tpReq.put("positionIdx", 0);
                
                if (order.getStopLoss() != null) {
                    tpReq.put("stopLoss", order.getStopLoss());
                }
                
                log.info("Ustawianie partial TP {} - parametry: {}", tpNumber, tpReq);
                bybitApiClient.setTradingStop(tpReq);
                
                // Oznacz TP jako przetworzony
                tp.setProcessed(true);
                
                if (!isLast) {
                    remainingQty -= tpSize;
                }
            }
            
            // Oznacz zlecenie jako przetworzone
            order.setStatus("PROCESSED_TP_SL");
            log.info("Wszystkie TP dla zlecenia {} zostały skonfigurowane", order.getOrderId());
            
        } catch (Exception e) {
            log.error("Błąd podczas konfiguracji take-profits dla zlecenia {}: {}", 
                    order.getOrderId(), e.getMessage(), e);
            // Nie zmieniamy statusu zlecenia, żeby spróbować ponownie przy następnym sprawdzeniu
        }
    }
} 