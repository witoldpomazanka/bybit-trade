package com.mulaczos.blofin_trade.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "limit_orders")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LimitOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private String orderId;

    @Column(nullable = false)
    private String symbol;

    @Column(nullable = false)
    private String side;

    @Column(nullable = false)
    private String quantity;

    @Column(name = "entry_price", nullable = false)
    private String entryPrice;

    @Column(name = "stop_loss")
    private String stopLoss;

    @Column(nullable = false)
    private Integer leverage;

    @Column(nullable = false)
    private String status;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "filled_at")
    private LocalDateTime filledAt;

    @Column(name = "last_checked_at", nullable = false)
    private LocalDateTime lastCheckedAt;

    @OneToMany(mappedBy = "limitOrder", cascade = CascadeType.ALL, fetch = FetchType.EAGER)
    private List<LimitOrderTakeProfit> takeProfits = new ArrayList<>();
}

