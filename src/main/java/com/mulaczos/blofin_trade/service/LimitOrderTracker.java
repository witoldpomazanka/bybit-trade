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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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

    @Value("${limit-order.tracker.check-interval:30000}")
    private long checkInterval;

    @Scheduled(fixedDelayString = "${limit-order.tracker.check-interval:30000}")
    @Transactional
    public void checkPendingOrders() {
        List<LimitOrder> pendingOrders = limitOrderService.getPendingOrders();

        if (pendingOrders.isEmpty()) {
            return;
        }

        log.debug("Znaleziono {} oczekujących zleceń limit", pendingOrders.size());

        for (LimitOrder order : pendingOrders) {
            try {
                checkOrderStatus(order);
            } catch (Exception e) {
                log.error("Błąd podczas sprawdzania zlecenia {}: {}", order.getOrderId(), e.getMessage(), e);
            }
        }
    }

    private void checkOrderStatus(LimitOrder order) {
        order.setLastCheckedAt(LocalDateTime.now());

        JsonNode orderDetailsResponse = blofinApiClient.getOrderDetails(order.getSymbol(), order.getOrderId());
        
        if (orderDetailsResponse.has("data") && orderDetailsResponse.get("data").isArray() && orderDetailsResponse.get("data").size() > 0) {
            JsonNode orderData = orderDetailsResponse.get("data").get(0);
            String apiStatus = orderData.has("state") ? orderData.get("state").asText() : "";
            
            log.info("Status zlecenia {} na Blofinie: {}", order.getOrderId(), apiStatus);

            if ("filled".equalsIgnoreCase(apiStatus)) {
                log.info("Zlecenie {} zostało WYPEŁNIONE. Konfiguruję TP i SL.", order.getOrderId());
                order.setStatus("FILLED");
                order.setFilledAt(LocalDateTime.now());
                
                String filledQty = orderData.has("fillSz") ? orderData.get("fillSz").asText() : order.getQuantity();
                executeFullPositionConfiguration(order, filledQty);
                
                twilioNotificationService.sendPositionOpenedNotification(
                        order.getSymbol(),
                        order.getSide(),
                        filledQty,
                        order.getLeverage(),
                        "TP/SL Batch"
                );
            } else if ("canceled".equalsIgnoreCase(apiStatus)) {
                log.info("Zlecenie {} zostało ANULOWANE. Przerywam proces.", order.getOrderId());
                order.setStatus("ABORTED");
            }
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

            BigDecimal totalQty = new BigDecimal(filledQty);
            BigDecimal lotSize = blofinIntegrationService.getQuantityStep(order.getSymbol());
            
            int tpCount = takeProfits.size();
            // Ilość na jeden TP: totalQty / tpCount, zaokrąglone w dół do lotSize
            BigDecimal baseTpQty = totalQty.divide(BigDecimal.valueOf(tpCount), 8, RoundingMode.DOWN);
            baseTpQty = blofinIntegrationService.roundToValidQuantity(baseTpQty, lotSize);
            // roundToValidQuantity używa RoundingMode.UP, ale chcemy RoundingMode.DOWN zgodnie z instrukcją "zaokrąglaj w dół"
            // Poprawmy to lokalnie lub użyjmy dedykowanej logiki.
            
            // Re-implementing rounding down to lotSize
            baseTpQty = totalQty.divide(BigDecimal.valueOf(tpCount), 8, RoundingMode.DOWN);
            BigDecimal multiplier = baseTpQty.divide(lotSize, 0, RoundingMode.DOWN);
            baseTpQty = multiplier.multiply(lotSize);

            List<Map<String, String>> batchOrders = new ArrayList<>();
            BigDecimal remainingQty = totalQty;
            String tpSide = "buy".equalsIgnoreCase(order.getSide()) ? "sell" : "buy";

            for (int i = 0; i < tpCount; i++) {
                LimitOrderTakeProfit tp = takeProfits.get(i);
                boolean isLast = (i == tpCount - 1);
                BigDecimal currentTpQty = isLast ? remainingQty : baseTpQty;

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

            log.info("Wysyłanie batcha {} zleceń TP dla {}", batchOrders.size(), order.getOrderId());
            blofinApiClient.placeBatchOrders(batchOrders);

            // Stop Loss jako Algo Order (Stop Market)
            if (order.getStopLoss() != null && !order.getStopLoss().isEmpty()) {
                log.info("Wysyłanie zlecenia Stop Loss (Stop Market) dla {}", order.getOrderId());
                blofinApiClient.placeAlgoOrder(
                        order.getSymbol(),
                        tpSide,
                        "stop_market",
                        totalQty.toPlainString(),
                        order.getStopLoss(),
                        null,
                        true
                );
            }

            order.setStatus("PROCESSED_TP_SL");
        } catch (Exception e) {
            log.error("Błąd podczas konfiguracji TP/SL dla zlecenia {}: {}", order.getOrderId(), e.getMessage(), e);
        }
    }
}
