package com.mulaczos.blofin_trade.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.mulaczos.blofin_trade.model.LimitOrder;
import com.mulaczos.blofin_trade.model.LimitOrderTakeProfit;

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

        log.debug("Sprawdzanie zleceń limit (oczekujące: {})", pendingOrders.size());

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
            // Pobranie aktualnej ceny rynkowej na początek
            double currentMarketPrice = 0;
            try {
                currentMarketPrice = blofinApiClient.getMarketPrice("linear", order.getSymbol());
            } catch (Exception e) {
                log.debug("Nie udało się pobrać ceny rynkowej dla {}: {}", order.getSymbol(), e.getMessage());
            }

            // 1. Sprawdźmy wpierw open orders (oczekujące)
            JsonNode openOrdersResponse = blofinApiClient.getOpenOrders(order.getSymbol());
            if (openOrdersResponse.has("data") && openOrdersResponse.get("data").isArray()) {
                for (JsonNode o : openOrdersResponse.get("data")) {
                    if (o.has("orderId") && o.get("orderId").asText().equals(order.getOrderId())) {
                        log.info("Zlecenie {} - czeka na: {} [{}] (aktualna cena: ${}, entry: ${}, qty: {})",
                                order.getOrderId(),
                                order.getSide(),
                                order.getSymbol(),
                                String.format("%.2f", currentMarketPrice),
                                order.getEntryPrice(),
                                order.getQuantity());
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

                    log.debug("Status zlecenia {}: {}, qty={}", order.getOrderId(), state, filledQty);

                    if ("filled".equalsIgnoreCase(state)) {
                        log.info("✓ Zlecenie {} WYKONANE (qty={})", order.getOrderId(), filledQty);
                        processFilledOrder(order, filledQty, "Limit (API)");
                        return;
                    } else if ("canceled".equalsIgnoreCase(state)) {
                        log.info("✗ Zlecenie {} ANULOWANE", order.getOrderId());
                        order.setStatus("ABORTED");
                        return;
                    }
                }
            } catch (com.mulaczos.blofin_trade.exception.BlofinApiException ex) {
                if (com.mulaczos.blofin_trade.exception.BlofinApiException.OPERATION_NOT_SUPPORTED.equals(ex.getApiCode())) {
                    log.debug("Zlecenie {} - brak w API (152404), przechodzę do fallbacku", order.getOrderId());
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
                        log.info("✓ Zlecenie {} WYKONANE (fallback, qty={})", order.getOrderId(), size);
                        processFilledOrder(order, String.valueOf(size), "Limit (Fallback)");
                        return;
                    }
                }
            }
            if (!detailsChecked) {
                log.warn("Zlecenie {} - nie odnaleziono, markuję jako ABORTED - prawdopodobnie problem jest między klawiaturą a ekranem HEH", order.getOrderId());
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
            BigDecimal minSize = blofinIntegrationService.getMinimumOrderQuantity(order.getSymbol());
            
            int tpCount = takeProfits.size();
            int maxPossibleTps = totalQty.divide(minSize, 0, RoundingMode.DOWN).intValue();
            int actualTpCount = Math.min(tpCount, maxPossibleTps);
            
            if (actualTpCount == 0) {
                log.error("Nie można ustawić Partial TP/SL dla {}: za mało kontraktów ({})", order.getOrderId(), totalQty.toPlainString());
                order.setStatus("PROCESSED_TP_SL");
                return;
            }

            BigDecimal remainingQty = totalQty;
            String tpSide = "buy".equalsIgnoreCase(order.getSide()) ? "sell" : "buy";

            for (int i = 0; i < takeProfits.size(); i++) {
                LimitOrderTakeProfit tp = takeProfits.get(i);
                boolean isLast = (i == takeProfits.size() - 1);
                BigDecimal tpSize;

                if (isLast) {
                    tpSize = remainingQty;
                } else {
                    BigDecimal idealPart = totalQty.divide(BigDecimal.valueOf(actualTpCount), 8, RoundingMode.DOWN);
                    tpSize = blofinIntegrationService.roundToValidQuantity(idealPart, lotSize, RoundingMode.DOWN);

                    if (tpSize.compareTo(minSize) < 0) tpSize = minSize;
                    if (remainingQty.subtract(tpSize).compareTo(minSize) < 0) {
                        tpSize = remainingQty;
                        isLast = true;
                    }
                }

                Map<String, Object> tpReq = new HashMap<>();
                tpReq.put("category", "linear");
                tpReq.put("symbol", order.getSymbol());
                tpReq.put("tpslMode", "Partial");
                tpReq.put("tpOrderType", "Market");
                tpReq.put("tpSize", tpSize.toPlainString());
                tpReq.put("takeProfit", tp.getPrice());
                tpReq.put("positionIdx", 0);
                tpReq.put("side", tpSide);

                if (order.getStopLoss() != null && !order.getStopLoss().isEmpty()) {
                    tpReq.put("stopLoss", order.getStopLoss());
                }

                log.info("Ustawianie TP/SL-Limit#{} dla {}: cena={}, size={}, side={}", (i + 1), order.getSymbol(), tp.getPrice(), tpSize.toPlainString(), tpSide);
                blofinIntegrationService.callTradingStop(tpReq);

                remainingQty = remainingQty.subtract(tpSize);
                tp.setProcessed(true);
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
                order.setCurrentSlPrice(order.getStopLoss());
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
