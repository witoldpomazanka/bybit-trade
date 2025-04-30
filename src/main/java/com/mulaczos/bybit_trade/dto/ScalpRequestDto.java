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
public class ScalpRequestDto {
    private String coin;
    private Integer leverage;
    private BigDecimal usdtPrice;
    private BigDecimal usdtAmount;
} 