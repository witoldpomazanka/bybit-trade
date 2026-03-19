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

        try {
            // Pobieramy listę wszystkich aktywnych zleceń, aby sprawdzić czy nasze wciąż tam jest
            JsonNode openOrdersResponse = blofinApiClient.getOpenOrders(order.getSymbol());
            boolean orderFoundInOpen = false;
            
            if (openOrdersResponse.has("data") && openOrdersResponse.get("data").isArray()) {
                for (JsonNode openOrder : openOrdersResponse.get("data")) {
                    if (openOrder.has("orderId") && openOrder.get("orderId").asText().equals(order.getOrderId())) {
                        orderFoundInOpen = true;
                        log.debug("Zlecenie {} wciąż oczekuje (live).", order.getOrderId());
                        break;
                    }
                }
            }

            if (!orderFoundInOpen) {
                // Jeśli nie ma go w otwartych, to albo wypełnione, albo anulowane.
                // Używamy getPositions, aby sprawdzić czy pozycja się pojawiła (Filled)
                JsonNode positionsResponse = blofinApiClient.getPositions(true);
                boolean positionFound = false;

                if (positionsResponse.has("data") && positionsResponse.get("data").isArray()) {
                    String expectedInstId = order.getSymbol().contains("-") ? order.getSymbol() : order.getSymbol().replace("USDT", "-USDT");
                    for (JsonNode pos : positionsResponse.get("data")) {
                        String posInstId = pos.has("instId") ? pos.get("instId").asText() : "";
                        double size = pos.has("positions") ? Math.abs(pos.get("positions").asDouble()) : 0;
                        
                        if (posInstId.equals(expectedInstId) && size > 0) {
                            positionFound = true;
                            log.info("Zlecenie {} zniknęło z otwartych, ale znaleziono aktywną pozycję. Markujemy jako FILLED.", order.getOrderId());
                            
                            order.setStatus("FILLED");
                            order.setFilledAt(LocalDateTime.now());
                            
                            executeFullPositionConfiguration(order, String.valueOf(size));
                            
                            twilioNotificationService.sendPositionOpenedNotification(
                                    order.getSymbol(),
                                    order.getSide(),
                                    String.valueOf(size),
                                    order.getLeverage(),
                                    "TP/SL Batch (Fallback)"
                            );
                            break;
                        }
                    }
                }

                if (!positionFound) {
                    log.info("Zlecenie {} zniknęło z otwartych i nie ma aktywnej pozycji. Markujemy jako ABORTED (Canceled/Expired).", order.getOrderId());
                    order.setStatus("ABORTED");
                }
            }
        } catch (Exception e) {
            log.error("Błąd podczas sprawdzania statusu zlecenia {} (fallback mode): {}", order.getOrderId(), e.getMessage());
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
            JsonNode batchResponse = blofinApiClient.placeBatchOrders(batchOrders);
            log.debug("Odpowiedź batch TP dla {}: {}", order.getOrderId(), batchResponse);

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
                log.debug("Odpowiedź algo SL dla {}: {}", order.getOrderId(), algoResponse);
            }

            order.setStatus("PROCESSED_TP_SL");
        } catch (Exception e) {
            log.error("Błąd podczas konfiguracji TP/SL dla zlecenia {}: {}", order.getOrderId(), e.getMessage(), e);
        }
    }
}
