package com.mulaczos.bybit_trade.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Map;
import java.util.TreeMap;

@Data
@Builder
public class AdvancedMarketPositionRequest {

    @NotBlank(message = "Coin nie może być pusty")
    private String coin;

    @NotNull(message = "Leverage jest wymagane")
    @DecimalMin(value = "1", message = "Leverage musi być większe lub równe 1")
    private Integer leverage;

    @NotBlank(message = "Type nie może być pusty")
    @Pattern(regexp = "^(LONG|SHORT)$", message = "Type musi być LONG lub SHORT")
    private String type;

    @DecimalMin(value = "0", message = "UsdtAmount musi być większe lub równe 0")
    private BigDecimal usdtAmount;

    private String stopLoss;
    private String takeProfit;
    
    // Nowe pola dla zleceń limit
    private String orderType;
    private String entryPrice;

    // TP1-TP5
    private Map<Integer, String> partialTakeProfits;

    public static AdvancedMarketPositionRequest fromMap(Map<String, Object> payload) {
        if (!payload.containsKey("coin") || !payload.containsKey("leverage") || !payload.containsKey("type")) {
            throw new IllegalArgumentException("Brak wymaganych pól: coin, leverage, type");
        }

        try {
            AdvancedMarketPositionRequestBuilder builder = AdvancedMarketPositionRequest.builder()
                    .coin(payload.get("coin").toString().toUpperCase())
                    .leverage(Integer.parseInt(payload.get("leverage").toString()))
                    .type(payload.get("type").toString().toUpperCase());

            // Opcjonalne pola
            if (payload.containsKey("usdtAmount") && payload.get("usdtAmount") != null) {
                builder.usdtAmount(new BigDecimal(payload.get("usdtAmount").toString()));
            }

            if (payload.containsKey("stopLoss") && payload.get("stopLoss") != null) {
                builder.stopLoss(payload.get("stopLoss").toString());
            }

            if (payload.containsKey("takeProfit") && payload.get("takeProfit") != null) {
                builder.takeProfit(payload.get("takeProfit").toString());
            }
            
            // Nowe pola dla zleceń limit
            if (payload.containsKey("orderType") && payload.get("orderType") != null) {
                builder.orderType(payload.get("orderType").toString());
            }
            
            if (payload.containsKey("entryPrice") && payload.get("entryPrice") != null) {
                builder.entryPrice(payload.get("entryPrice").toString());
            }

            // Zbieranie partial take-profits
            Map<Integer, String> partialTps = new TreeMap<>(); // TreeMap dla zachowania kolejności
            for (int i = 1; i <= 5; i++) {
                String tpKey = "tp" + i;
                if (payload.containsKey(tpKey) && payload.get(tpKey) != null) {
                    partialTps.put(i, payload.get(tpKey).toString());
                }
            }
            if (!partialTps.isEmpty()) {
                builder.partialTakeProfits(partialTps);
            }

            return builder.build();
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Błąd konwersji wartości numerycznych: " + e.getMessage());
        } catch (Exception e) {
            throw new IllegalArgumentException("Błąd podczas tworzenia requestu: " + e.getMessage());
        }
    }

    public boolean hasPartialTakeProfits() {
        return partialTakeProfits != null && !partialTakeProfits.isEmpty();
    }

    public boolean isLong() {
        return "LONG".equals(type);
    }

    public String getSide() {
        return isLong() ? "Buy" : "Sell";
    }
    
    public boolean isLimit() {
        return "Limit".toLowerCase().equalsIgnoreCase(orderType);
    }
} 