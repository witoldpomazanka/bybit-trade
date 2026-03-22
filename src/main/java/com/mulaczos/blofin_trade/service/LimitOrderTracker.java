package com.mulaczos.blofin_trade.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.mulaczos.blofin_trade.model.LimitOrder;
import com.mulaczos.blofin_trade.model.LimitOrderTakeProfit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.math.RoundingMode;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@Slf4j
public class LimitOrderTracker {

    private final LimitOrderService limitOrderService;
    private final BlofinApiClient blofinApiClient;
    private final BlofinIntegrationService blofinIntegrationService;
    private final TwilioNotificationService twilioNotificationService;

    @Scheduled(fixedDelayString = "${limit-order.tracker.check-interval:30000}")
    @Transactional
    public void checkPendingOrders() {
        List<LimitOrder> pendingOrders = limitOrderService.getPendingOrders();

        if (pendingOrders.isEmpty()) {
            return;
        }

        log.info("Sprawdzanie zleceń limit (oczekujące: {})", pendingOrders.size());

        for (LimitOrder order : pendingOrders) {
            try {
                checkOrderStatus(order);
            } catch (Exception e) {
                log.error("Błąd checkPendingOrders (ID: {}): {}", order.getOrderId(), e.getMessage());
            }
        }
    }

    private void checkOrderStatus(LimitOrder order) {
        order.setLastCheckedAt(LocalDateTime.now());

        try {
            // 1. Sprawdźmy wpierw open orders (oczekujące)
            JsonNode openOrdersResponse = blofinApiClient.getOpenOrders(order.getSymbol());
            if (openOrdersResponse.has("data") && openOrdersResponse.get("data").isArray()) {
                for (JsonNode o : openOrdersResponse.get("data")) {
                    if (o.has("orderId") && o.get("orderId").asText().equals(order.getOrderId())) {
                        log.info("Zlecenie {} - wciąż oczekuje (live)", order.getOrderId());
                        return;
                    }
                }
            }

            boolean detailsChecked = false;
            try {
                // 2. Pobranie szczegółów zlecenia
                JsonNode orderDetailsResponse = blofinApiClient.getOrderDetails(order.getSymbol(), order.getOrderId());
                
                if (orderDetailsResponse.has("data") && orderDetailsResponse.get("data").isArray() 
                        && !orderDetailsResponse.get("data").isEmpty()) {
                    
                    detailsChecked = true;
                    JsonNode orderData = orderDetailsResponse.get("data").get(0);
                    String state = orderData.has("state") ? orderData.get("state").asText() : "";
                    String filledQty = orderData.has("filledQty") ? orderData.get("filledQty").asText() : "0";

                    log.info("Status zlecenia {}: {}, qty={}", order.getOrderId(), state, filledQty);

                    if ("filled".equalsIgnoreCase(state)) {
                        processFilledOrder(order, filledQty, "Limit (API)");
                        return;
                    } else if ("canceled".equalsIgnoreCase(state)) {
                        log.info("Zlecenie {} anulowane - ABORTED", order.getOrderId());
                        order.setStatus("ABORTED");
                        return;
                    }
                }
            } catch (com.mulaczos.blofin_trade.exception.BlofinApiException ex) {
                if (com.mulaczos.blofin_trade.exception.BlofinApiException.OPERATION_NOT_SUPPORTED.equals(ex.getApiCode())) {
                    log.info("Zlecenie {} - brak w API (152404), przechodzę do fallbacku", order.getOrderId());
                } else {
                    log.error("Błąd API getOrderDetails ({}): {}", order.getOrderId(), ex.getApiMsg());
                }
            } catch (Exception ex) {
                log.warn("Błąd detali zlecenia {}: {}", order.getOrderId(), ex.getMessage());
            }

            // 3. FALLBACK - sprawdzenie pozycji
            checkStatusByPositionsFallback(order, detailsChecked);
        } catch (Exception e) {
            log.error("Krytyczny błąd weryfikacji zlecenia {}: {}", order.getOrderId(), e.getMessage());
        }
    }

    private void processFilledOrder(LimitOrder order, String filledQty, String source) {
        log.info("Wypełniono {} ({}). Konfiguracja TP/SL...", order.getOrderId(), source);
        order.setStatus("FILLED");
        order.setFilledAt(LocalDateTime.now());
        
        executeFullPositionConfiguration(order, filledQty);
        
        BigDecimal filledValue = calculateUsdtValue(order.getSymbol(), new BigDecimal(filledQty), new BigDecimal(order.getEntryPrice()));
        
        twilioNotificationService.sendPositionOpenedNotification(
                order.getSymbol(),
                order.getSide(),
                filledQty,
                order.getLeverage(),
                source,
                order.getEntryPrice(),
                filledValue.toPlainString()
        );
    }

    private void checkStatusByPositionsFallback(LimitOrder order, boolean detailsChecked) {
        try {
            JsonNode positionsResponse = blofinApiClient.getPositions(true);
            if (positionsResponse.has("data") && positionsResponse.get("data").isArray()) {
                String expectedInstId = order.getSymbol().contains("-") ? order.getSymbol() : order.getSymbol().replace("USDT", "-USDT");
                for (JsonNode pos : positionsResponse.get("data")) {
                    String posInstId = pos.has("instId") ? pos.get("instId").asText() : "";
                    double size = pos.has("positions") ? Math.abs(pos.get("positions").asDouble()) : 0;
                    
                    if ((posInstId.equals(expectedInstId) || posInstId.replace("-", "").equals(order.getSymbol())) && size > 0) {
                        processFilledOrder(order, String.valueOf(size), "Limit (Fallback)");
                        return;
                    }
                }
            }
            if (!detailsChecked) {
                log.info("Zlecenie {} - nie odnaleziono, markuję jako ABORTED", order.getOrderId());
                order.setStatus("ABORTED");
            }
        } catch (Exception e) {
            log.error("Błąd fallback dla {}: {}", order.getOrderId(), e.getMessage());
        }
    }

    private void executeFullPositionConfiguration(LimitOrder order, String filledQty) {
        try {
            List<LimitOrderTakeProfit> takeProfits = limitOrderService.getUnprocessedTakeProfitsForOrder(order.getId());
            if (takeProfits.isEmpty()) {
                log.info("Brak take-profits do wystawienia dla zlecenia: {}", order.getOrderId());
                order.setStatus("PROCESSED_TP_SL");
                return;
            }

            log.info("Konfiguracja TP (n={}) dla {}", takeProfits.size(), order.getOrderId());
            
            BigDecimal totalQty = new BigDecimal(filledQty);
            BigDecimal lotSize = blofinIntegrationService.getQuantityStep(order.getSymbol());
            
            BigDecimal baseTpQty = totalQty.divide(BigDecimal.valueOf(takeProfits.size()), 8, RoundingMode.DOWN);
            BigDecimal multiplier = baseTpQty.divide(lotSize, 0, RoundingMode.DOWN);
            baseTpQty = multiplier.multiply(lotSize);

            List<Map<String, String>> batchOrders = new ArrayList<>();
            BigDecimal remainingQty = totalQty;
            String tpSide = "buy".equalsIgnoreCase(order.getSide()) ? "sell" : "buy";

            for (int i = 0; i < takeProfits.size(); i++) {
                LimitOrderTakeProfit tp = takeProfits.get(i);
                boolean isLast = (i == takeProfits.size() - 1);
                BigDecimal currentTpQty = isLast ? remainingQty : baseTpQty;

                if (currentTpQty.compareTo(BigDecimal.ZERO) <= 0) {
                    log.warn("Pominięto TP#{} dla {} - zerowa ilość", (i+1), order.getOrderId());
                    continue;
                }

                Map<String, String> tpOrder = new HashMap<>();
                tpOrder.put("instId", order.getSymbol().contains("-") ? order.getSymbol() : order.getSymbol().replace("USDT", "-USDT"));
                tpOrder.put("marginMode", "isolated");
                tpOrder.put("positionSide", "net");
                tpOrder.put("side", tpSide);
                tpOrder.put("orderType", "limit");
                tpOrder.put("size", currentTpQty.toPlainString());
                tpOrder.put("price", tp.getPrice());
                tpOrder.put("reduceOnly", "true");
                
                batchOrders.add(tpOrder);
                remainingQty = remainingQty.subtract(currentTpQty);
                tp.setProcessed(true);
            }

            if (!batchOrders.isEmpty()) {
                JsonNode batchResponse = blofinApiClient.placeBatchOrders(batchOrders);
                log.info("Wynik batch TP ({}): {}", order.getOrderId(), batchResponse);
            }

            // Stop Loss jako Algo Order (Stop Market)
            if (order.getStopLoss() != null && !order.getStopLoss().isEmpty()) {
                log.info("Wysyłanie zlecenia Stop Loss (Stop Market) dla {}", order.getOrderId());
                JsonNode algoResponse = blofinApiClient.placeAlgoOrder(
                        order.getSymbol(),
                        tpSide,
                        "stop_market",
                        totalQty.toPlainString(),
                        order.getStopLoss(),
                        null,
                        true
                );
                log.info("Wynik SL dla {}: {}", order.getOrderId(), algoResponse);
            }

            order.setStatus("PROCESSED_TP_SL");
        } catch (Exception e) {
            log.error("Błąd konfiguracji TP/SL dla {}: {}", order.getOrderId(), e.getMessage());
        }
    }

    private BigDecimal calculateUsdtValue(String symbol, BigDecimal quantity, BigDecimal price) {
        try {
            BigDecimal ctVal = blofinIntegrationService.getContractValue(symbol);
            return quantity.multiply(ctVal).multiply(price);
        } catch (IOException e) {
            log.warn("Nie udało się pobrać ctVal dla {}, liczę bez (1:1): {}", symbol, e.getMessage());
            return quantity.multiply(price);
        }
    }
}
