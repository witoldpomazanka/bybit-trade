package com.bybit.trade.model.dto;

import com.bybit.trade.model.PositionStatus;
import com.bybit.trade.model.PositionType;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PositionResponseDto {
    
    private Long id;
    private String symbol;
    private PositionType type;
    private BigDecimal amount;
    private BigDecimal entryPrice;
    private BigDecimal takeProfitPrice;
    private BigDecimal stopLossPrice;
    private LocalDateTime openTime;
    private LocalDateTime closeTime;
    private BigDecimal closePrice;
    private BigDecimal profit;
    private PositionStatus status;
} 