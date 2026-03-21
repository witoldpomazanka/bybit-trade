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
        order.setLastCheckedAt(LocalDateTime.now());

        try {
            // Pobieramy szczegóły zlecenia, aby sprawdzić jego realny status
            JsonNode orderDetailsResponse = blofinApiClient.getOrderDetails(order.getSymbol(), order.getOrderId());
            
            if (orderDetailsResponse.has("data") && orderDetailsResponse.get("data").isArray() 
                    && !orderDetailsResponse.get("data").isEmpty()) {
                
                JsonNode orderData = orderDetailsResponse.get("data").get(0);
                String state = orderData.has("state") ? orderData.get("state").asText() : "";
                String filledQty = orderData.has("filledQty") ? orderData.get("filledQty").asText() : "0";

                log.info("Status zlecenia {} na giełdzie: {}, wypełniono: {}", order.getOrderId(), state, filledQty);

                if ("filled".equalsIgnoreCase(state)) {
                    log.info("Zlecenie {} zostało wypełnione. Rozpoczynam konfigurację TP/SL.", order.getOrderId());
                    order.setStatus("FILLED");
                    order.setFilledAt(LocalDateTime.now());
                    
                    executeFullPositionConfiguration(order, filledQty);
                    
                    // Wartość USDT dla SMS - uwzględniamy Contract Value
                    BigDecimal filledValue = calculateUsdtValue(order.getSymbol(), new BigDecimal(filledQty), new BigDecimal(order.getEntryPrice()));
                    
                    twilioNotificationService.sendPositionOpenedNotification(
                            order.getSymbol(),
                            order.getSide(),
                            filledQty,
                            order.getLeverage(),
                            "Limit (Filled)",
                            order.getEntryPrice(),
                            filledValue.toPlainString()
                    );
                } else if ("canceled".equalsIgnoreCase(state)) {
                    log.info("Zlecenie {} zostało anulowane na giełdzie. Markujemy jako ABORTED.", order.getOrderId());
                    order.setStatus("ABORTED");
                } else {
                    log.info("Zlecenie {} wciąż oczekuje (status: {}).", order.getOrderId(), state);
                }
            } else {
                // Jeśli nie ma danych o zleceniu, może to być błąd API lub zlecenie wygasło
                log.warn("Brak danych w odpowiedzi dla zlecenia {}. Sprawdzam pozycje jako fallback.", order.getOrderId());
                checkStatusByPositionsFallback(order);
            }
        } catch (Exception e) {
            log.error("Błąd podczas sprawdzania statusu zlecenia {}: {}", order.getOrderId(), e.getMessage());
            // W przypadku błędu API (np. 152404), próbujemy sprawdzić pozycje
            checkStatusByPositionsFallback(order);
        }
    }

    private void checkStatusByPositionsFallback(LimitOrder order) {
        try {
            JsonNode positionsResponse = blofinApiClient.getPositions(true);
            if (positionsResponse.has("data") && positionsResponse.get("data").isArray()) {
                String expectedInstId = order.getSymbol().contains("-") ? order.getSymbol() : order.getSymbol().replace("USDT", "-USDT");
                for (JsonNode pos : positionsResponse.get("data")) {
                    String posInstId = pos.has("instId") ? pos.get("instId").asText() : "";
                    double size = pos.has("positions") ? Math.abs(pos.get("positions").asDouble()) : 0;
                    
                    if ((posInstId.equals(expectedInstId) || posInstId.replace("-", "").equals(order.getSymbol())) && size > 0) {
                        log.info("Znaleziono aktywną pozycję dla zlecenia {} przez fallback. Markujemy jako FILLED.", order.getOrderId());
                        order.setStatus("FILLED");
                        order.setFilledAt(LocalDateTime.now());
                        
                        String sizeStr = String.valueOf(size);
                        executeFullPositionConfiguration(order, sizeStr);
                        
                        BigDecimal filledValue = calculateUsdtValue(order.getSymbol(), BigDecimal.valueOf(size), new BigDecimal(order.getEntryPrice()));
                        twilioNotificationService.sendPositionOpenedNotification(
                                order.getSymbol(),
                                order.getSide(),
                                sizeStr,
                                order.getLeverage(),
                                "Limit (Fallback)",
                                order.getEntryPrice(),
                                filledValue.toPlainString()
                        );
                        return;
                    }
                }
            }
            log.info("Zlecenie {} nie zostało odnalezione w pozycjach. Markujemy jako ABORTED (Canceled/Expired).", order.getOrderId());
            order.setStatus("ABORTED");
        } catch (Exception e) {
            log.error("Błąd w fallbacku dla zlecenia {}: {}", order.getOrderId(), e.getMessage());
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
            log.info("Odpowiedź batch TP dla {}: {}", order.getOrderId(), batchResponse);

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
                log.info("Odpowiedź algo SL dla {}: {}", order.getOrderId(), algoResponse);
            }

            order.setStatus("PROCESSED_TP_SL");
        } catch (Exception e) {
            log.error("Błąd podczas konfiguracji TP/SL dla zlecenia {}: {}", order.getOrderId(), e.getMessage(), e);
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
