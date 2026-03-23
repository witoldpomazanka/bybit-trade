package com.mulaczos.blofin_trade.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.mulaczos.blofin_trade.model.LimitOrder;
import com.mulaczos.blofin_trade.model.LimitOrderTakeProfit;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class TradeMonitoringService {

    private final BlofinWebSocketClient webSocketClient;
    private final LimitOrderService limitOrderService;
    private final BlofinApiClient blofinApiClient;
    private final TwilioNotificationService twilioNotificationService;

    @PostConstruct
    public void init() {
        webSocketClient.setMessageHandler(this::handleWebSocketMessage);
    }

    private void handleWebSocketMessage(JsonNode node) {
        if (!node.has("table") || !"orders".equals(node.get("table").asText())) {
            return;
        }

        JsonNode data = node.get("data");
        if (data == null || !data.isArray()) {
            return;
        }

        for (JsonNode orderNode : data) {
            String state = orderNode.path("state").asText();
            if (!"filled".equalsIgnoreCase(state)) {
                continue;
            }

            String instId = orderNode.path("instId").asText();
            String price = orderNode.path("price").asText();
            String size = orderNode.path("filledQty").asText();

            log.info("Wykryto zrealizowane zlecenie przez WS: instId={}, price={}, size={}", instId, price, size);

            checkAndMoveStopLoss(instId, price);
        }
    }

    private void checkAndMoveStopLoss(String instId, String executionPriceStr) {
        String symbol = instId.replace("-", "");
        List<LimitOrder> activeOrders = limitOrderService.getLiveOrders(symbol);

        for (LimitOrder order : activeOrders) {
            Optional<LimitOrderTakeProfit> hitTp = order.getTakeProfits().stream()
                    .filter(tp -> !Boolean.TRUE.equals(tp.getProcessed()))
                    .filter(tp -> isPriceMatch(tp.getPrice(), executionPriceStr))
                    .findFirst();

            if (hitTp.isPresent()) {
                LimitOrderTakeProfit tp = hitTp.get();
                log.info("Zlecenie TP (pozycja {}) trafione dla {}: cena={}", tp.getPosition(), order.getSymbol(), tp.getPrice());

                processTpExecution(order, tp);
            }
        }
    }

    private void processTpExecution(LimitOrder order, LimitOrderTakeProfit hitTp) {
        hitTp.setProcessed(true);
        order.setLastTpHitPosition(hitTp.getPosition());

        String newSlPrice = null;
        if (hitTp.getPosition() == 1) {
            // TP1 -> SL na Entry
            newSlPrice = order.getEntryPrice();
        } else {
            // TPn -> SL na TP(n-1)
            int prevPos = hitTp.getPosition() - 1;
            Optional<LimitOrderTakeProfit> prevTp = order.getTakeProfits().stream()
                    .filter(tp -> tp.getPosition() == prevPos)
                    .findFirst();

            if (prevTp.isPresent()) {
                newSlPrice = prevTp.get().getPrice();
            }
        }

        if (newSlPrice != null && !newSlPrice.equals(order.getCurrentSlPrice())) {
            updateStopLoss(order, newSlPrice);
        } else {
            limitOrderService.saveOrder(order);
        }
    }

    private void updateStopLoss(LimitOrder order, String newSlPrice) {
        try {
            log.info("Aktualizacja Stop Loss dla {}: {} -> {}", order.getSymbol(), order.getCurrentSlPrice(), newSlPrice);

            String side = "buy".equalsIgnoreCase(order.getSide()) ? "sell" : "buy";

            // Pobieramy aktualną wielkość pozycji, aby wiedzieć ile ustawić SL
            double currentPositionSize = 0;
            try {
                JsonNode positionsResponse = blofinApiClient.getPositions(true);
                if (positionsResponse.has("data") && positionsResponse.get("data").isArray()) {
                    String expectedInstId = order.getSymbol().contains("-") ? order.getSymbol() : order.getSymbol().replace("USDT", "-USDT");
                    for (JsonNode pos : positionsResponse.get("data")) {
                        String posInstId = pos.has("instId") ? pos.get("instId").asText() : "";
                        if (posInstId.equals(expectedInstId) || posInstId.replace("-", "").equals(order.getSymbol())) {
                            currentPositionSize = Math.abs(pos.path("positions").asDouble());
                            break;
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("Nie udało się pobrać wielkości pozycji dla {}, używam ilości z zamówienia: {}", order.getSymbol(), e.getMessage());
                currentPositionSize = Double.parseDouble(order.getQuantity());
            }

            if (currentPositionSize <= 0) {
                log.info("Pozycja dla {} zamknięta, pomijam aktualizację SL", order.getSymbol());
                order.setStatus("CLOSED");
                limitOrderService.saveOrder(order);
                return;
            }

            blofinApiClient.placeAlgoOrder(
                    order.getSymbol(),
                    side,
                    "stop_market",
                    String.valueOf(currentPositionSize),
                    newSlPrice,
                    null,
                    true
            );

            order.setCurrentSlPrice(newSlPrice);
            limitOrderService.saveOrder(order);

            twilioNotificationService.sendSimpleNotification(
                    String.format("SL Moved: %s to %s (TP%d hit)", order.getSymbol(), newSlPrice, order.getLastTpHitPosition())
            );
        } catch (Exception e) {
            log.error("Błąd aktualizacji SL dla {}: {}", order.getSymbol(), e.getMessage());
        }
    }

    private boolean isPriceMatch(String targetPrice, String executionPrice) {
        try {
            double p1 = Double.parseDouble(targetPrice);
            double p2 = Double.parseDouble(executionPrice);
            // Tolerancja 0.1% na wypadek poślizgu lub różnic w zaokrągleniach
            return Math.abs(p1 - p2) / p1 < 0.001;
        } catch (Exception e) {
            return false;
        }
    }
}
