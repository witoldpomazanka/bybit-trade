package com.bybit.trade.dto;

import lombok.Data;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;

@Data
public class PositionRequestDto {
    
    @NotNull(message = "Symbol jest wymagany")
    private String symbol;
    
    @NotNull(message = "Wielkość pozycji jest wymagana")
    @Positive(message = "Wielkość pozycji musi być większa od 0")
    private BigDecimal qty;
    
    private BigDecimal takeProfit;
    private BigDecimal stopLoss;

    /**
     * Konwertuje symbol na format Bybit (dodaje USDT)
     */
    public String getBybitSymbol() {
        return symbol.toUpperCase() + "USDT";
    }
} 