package com.mulaczos.blofin_trade.dto;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class InstrumentInfo {
    private String instId;
    private BigDecimal minSize;
    private BigDecimal lotSize;
    private BigDecimal contractValue;
    private BigDecimal tickSize;

    public static InstrumentInfo fromJson(JsonNode data) {
        if (data == null || data.isMissingNode()) return null;
        
        BigDecimal ctVal = BigDecimal.ONE;
        if (data.has("contractValue")) {
            ctVal = new BigDecimal(data.get("contractValue").asText());
        } else if (data.has("ctVal")) {
            ctVal = new BigDecimal(data.get("ctVal").asText());
        }

        return InstrumentInfo.builder()
                .instId(data.path("instId").asText())
                .minSize(new BigDecimal(data.path("minSize").asText("0")))
                .lotSize(new BigDecimal(data.path("lotSize").asText("0")))
                .contractValue(ctVal)
                .tickSize(new BigDecimal(data.path("tickSize").asText("0")))
                .build();
    }
}

