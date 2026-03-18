package com.mulaczos.blofin_trade.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.mulaczos.blofin_trade.model.LimitOrder;
import com.mulaczos.blofin_trade.model.LimitOrderTakeProfit;
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

        JsonNode positions = blofinApiClient.getPositions("linear", "USDT", true);
        boolean positionFound = false;

        if (positions.has("data") && positions.get("data").isArray()) {
            JsonNode positionsList = positions.get("data");
            for (JsonNode position : positionsList) {
                String posInstId = position.has("instId") ? position.get("instId").asText() : "";
                String expectedInstId = order.getSymbol().endsWith("USDT")
                        ? order.getSymbol().replace("USDT", "-USDT") : order.getSymbol();
                double posSize = position.has("positions")
                        ? Math.abs(position.get("positions").asDouble()) : 0;

                if ((posInstId.equals(expectedInstId) || posInstId.replace("-", "").equals(order.getSymbol()))
                        && posSize > 0) {

                    positionFound = true;
                    log.info("Znaleziono otwartą pozycję dla zlecenia limit: {}", order.getOrderId());

                    order.setStatus("FILLED");
                    order.setFilledAt(LocalDateTime.now());

                    configurePartialTakeProfits(order, position);

                    twilioNotificationService.sendPositionOpenedNotification(
                            order.getSymbol(),
                            order.getSide(),
                            order.getQuantity(),
                            order.getLeverage(),
                            "TPKI dla limitu"
                    );
                    break;
                }
            }
        }

        if (!positionFound) {
            log.debug("Nie znaleziono otwartej pozycji dla zlecenia: {}, symbol: {}", order.getOrderId(), order.getSymbol());
        }
    }

    private void configurePartialTakeProfits(LimitOrder order, JsonNode position) {
        try {
            List<LimitOrderTakeProfit> takeProfits = limitOrderService.getUnprocessedTakeProfitsForOrder(order.getId());
            if (takeProfits.isEmpty()) {
                log.info("Brak nieskonfigurowanych take-profits dla zlecenia: {}", order.getOrderId());
                order.setStatus("PROCESSED_TP_SL");
                return;
            }

            double positionSize = position.has("positions")
                    ? Math.abs(position.get("positions").asDouble()) : 0;
            log.info("Wielkość pozycji do konfiguracji TP: {}", positionSize);

            BigDecimal minQty = blofinIntegrationService.getMinimumOrderQuantity(order.getSymbol());
            log.info("Minimalny limit dla {}: {}", order.getSymbol(), minQty);

            int tpCount = takeProfits.size();
            double basePartSize = Math.floor((positionSize / tpCount) * 100) / 100.0;
            double remainingQty = positionSize;

            for (int i = 0; i < takeProfits.size(); i++) {
                LimitOrderTakeProfit tp = takeProfits.get(i);
                int tpNumber = tp.getPosition();
                boolean isLast = (i == takeProfits.size() - 1);

                double tpSize = isLast ? remainingQty : basePartSize;

                if (BigDecimal.valueOf(tpSize).compareTo(minQty) < 0) {
                    tpSize = minQty.doubleValue();
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
                blofinApiClient.setTradingStop(tpReq);

                tp.setProcessed(true);

                if (!isLast) {
                    remainingQty -= tpSize;
                }
            }

            order.setStatus("PROCESSED_TP_SL");
            log.info("Wszystkie TP dla zlecenia {} zostały skonfigurowane", order.getOrderId());

        } catch (Exception e) {
            log.error("Błąd podczas konfiguracji take-profits dla zlecenia {}: {}",
                    order.getOrderId(), e.getMessage(), e);
        }
    }
}

