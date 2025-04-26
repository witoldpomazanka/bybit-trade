package com.bybit.trade.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "bybit_positions", schema = "public")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Position {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String symbol;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PositionType type;

    @Column(nullable = false, precision = 20, scale = 8)
    private BigDecimal amount;

    @Column(nullable = false, precision = 20, scale = 8)
    private BigDecimal entryPrice;

    @Column(precision = 20, scale = 8)
    private BigDecimal takeProfitPrice;

    @Column(precision = 20, scale = 8)
    private BigDecimal stopLossPrice;

    @Column(nullable = false)
    private LocalDateTime openTime;

    private LocalDateTime closeTime;

    @Column(precision = 20, scale = 8)
    private BigDecimal closePrice;

    @Column(precision = 20, scale = 8)
    private BigDecimal profit;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PositionStatus status;

    // Dodatkowe pole opcjonalne do identyfikacji zewnętrznej
    private String externalOrderId;
} 