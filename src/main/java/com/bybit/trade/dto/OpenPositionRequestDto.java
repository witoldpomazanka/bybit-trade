package com.bybit.trade.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import lombok.Data;

@Data
public class OpenPositionRequestDto {
    
    @NotBlank(message = "Symbol nie może być pusty")
    private String coin;
    
    @NotBlank(message = "Typ pozycji nie może być pusty")
    @Pattern(regexp = "^(LONG|SHORT)$", message = "Typ pozycji musi być 'LONG' lub 'SHORT'")
    private String positionType;
    
    @NotNull(message = "Kwota USDT nie może być pusta")
    @Positive(message = "Kwota USDT musi być większa od 0")
    private Double usdtAmount;
    
    // Opcjonalna dźwignia, domyślnie null (co oznacza użycie dźwigni x10)
    private Integer leverage;
    
    private Double takeProfit;
    private Double stopLoss;
    
    // Dodatkowe pole do określenia, czy użyć zlecenia typu Post-Only Limit
    private boolean usePostOnlyLimit = true;
} 