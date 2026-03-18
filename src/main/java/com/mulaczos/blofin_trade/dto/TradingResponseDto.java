package com.mulaczos.blofin_trade.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TradingResponseDto {
    private String orderId;
    private String symbol;
    private String side;
    private String status;
    private double quantity;
}

