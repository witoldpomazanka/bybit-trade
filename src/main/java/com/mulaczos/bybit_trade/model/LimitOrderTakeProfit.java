package com.mulaczos.bybit_trade.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "limit_order_take_profits")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LimitOrderTakeProfit {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @ManyToOne
    @JoinColumn(name = "limit_order_id", nullable = false)
    private LimitOrder limitOrder;
    
    @Column(nullable = false)
    private Integer position;
    
    @Column(nullable = false)
    private String price;
    
    @Column(nullable = false)
    private Boolean processed;
} 