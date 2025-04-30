package com.mulaczos.bybit_trade.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TradingResponseDto {
    private String orderId;
    private String symbol;
    private String side;
    private String status;
    private BigDecimal quantity;
} 