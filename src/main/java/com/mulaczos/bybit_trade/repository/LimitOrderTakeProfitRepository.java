package com.mulaczos.bybit_trade.repository;

import com.mulaczos.bybit_trade.model.LimitOrderTakeProfit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface LimitOrderTakeProfitRepository extends JpaRepository<LimitOrderTakeProfit, Long> {
    
    List<LimitOrderTakeProfit> findByLimitOrderIdAndProcessed(Long limitOrderId, Boolean processed);
} 