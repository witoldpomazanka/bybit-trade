package com.mulaczos.bybit_trade.service;

import com.mulaczos.bybit_trade.dto.AdvancedMarketPositionRequest;
import com.mulaczos.bybit_trade.model.LimitOrder;
import com.mulaczos.bybit_trade.model.LimitOrderTakeProfit;
import com.mulaczos.bybit_trade.repository.LimitOrderRepository;
import com.mulaczos.bybit_trade.repository.LimitOrderTakeProfitRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class LimitOrderService {
    
    private final LimitOrderRepository limitOrderRepository;
    private final LimitOrderTakeProfitRepository takeProfitRepository;
    
    @Transactional
    public LimitOrder saveLimitOrder(String orderId, AdvancedMarketPositionRequest request, String symbol, String quantity) {
        log.info("Zapisywanie zlecenia limit: orderId={}, symbol={}, side={}, quantity={}", orderId, symbol, request.getSide(), quantity);
        
        LimitOrder order = LimitOrder.builder()
                .orderId(orderId)
                .symbol(symbol)
                .side(request.getSide())
                .quantity(quantity)
                .entryPrice(request.getEntryPrice())
                .stopLoss(request.getStopLoss())
                .leverage(request.getLeverage())
                .status("PENDING")
                .createdAt(LocalDateTime.now())
                .lastCheckedAt(LocalDateTime.now())
                .build();
        
        LimitOrder savedOrder = limitOrderRepository.save(order);
        
        // Zapisywanie take-profits
        if (request.hasPartialTakeProfits()) {
            List<LimitOrderTakeProfit> takeProfits = new ArrayList<>();
            for (Map.Entry<Integer, String> entry : request.getPartialTakeProfits().entrySet()) {
                LimitOrderTakeProfit tp = LimitOrderTakeProfit.builder()
                        .limitOrder(savedOrder)
                        .position(entry.getKey())
                        .price(entry.getValue())
                        .processed(false)
                        .build();
                
                takeProfits.add(tp);
            }
            takeProfitRepository.saveAll(takeProfits);
            log.info("Zapisano {} take-profitów dla zlecenia limit: {}", takeProfits.size(), orderId);
        }
        
        return savedOrder;
    }
    
    @Transactional(readOnly = true)
    public List<LimitOrder> getPendingOrders() {
        return limitOrderRepository.findByStatus("PENDING");
    }
    
    @Transactional
    public void updateOrderStatus(String orderId, String status) {
        log.info("Aktualizacja statusu zlecenia orderId={} na: {}", orderId, status);
        
        Optional<LimitOrder> orderOpt = limitOrderRepository.findByOrderId(orderId);
        if (orderOpt.isPresent()) {
            LimitOrder order = orderOpt.get();
            order.setStatus(status);
            
            if ("FILLED".equals(status)) {
                order.setFilledAt(LocalDateTime.now());
            }
            
            order.setLastCheckedAt(LocalDateTime.now());
            limitOrderRepository.save(order);
        } else {
            log.warn("Nie znaleziono zlecenia o ID: {}", orderId);
        }
    }
    
    @Transactional(readOnly = true)
    public List<LimitOrderTakeProfit> getUnprocessedTakeProfitsForOrder(Long orderId) {
        return takeProfitRepository.findByLimitOrderIdAndProcessed(orderId, false);
    }
} 