package com.bybit.trade.model.dto;

import com.bybit.trade.model.PositionType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PositionRequestDto {
    
    @NotBlank(message = "Symbol nie może być pusty")
    private String symbol;
    
    @NotNull(message = "Typ pozycji nie może być pusty")
    private PositionType type;
    
    @NotNull(message = "Ilość nie może być pusta")
    @Positive(message = "Ilość musi być większa od zera")
    private BigDecimal amount;
    
    private BigDecimal takeProfitPrice;
    
    private BigDecimal stopLossPrice;
} 