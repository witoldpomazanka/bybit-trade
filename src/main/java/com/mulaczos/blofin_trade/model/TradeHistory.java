package com.mulaczos.blofin_trade.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "trade_history")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TradeHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String symbol;

    @Column(nullable = false)
    private String side;

    @Column(nullable = false)
    private String quantity;

    @Column(name = "entry_price")
    private String entryPrice;

    @Column(name = "stop_loss")
    private String stopLoss;

    @Column(name = "take_profit")
    private String takeProfit;

    @Column(nullable = false)
    private Integer leverage;

    @Column(name = "chat_title")
    private String chatTitle;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "order_type")
    private String orderType;

    @Column(name = "usdt_amount")
    private String usdtAmount;
}

