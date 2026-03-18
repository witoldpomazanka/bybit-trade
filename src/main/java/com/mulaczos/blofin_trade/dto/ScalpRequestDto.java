package com.mulaczos.blofin_trade.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScalpRequestDto {
    private String coin;
    private Integer leverage;
    private double usdtPrice;
    private double usdtAmount;
}

