package com.mulaczos.blofin_trade.repository;

import com.mulaczos.blofin_trade.model.LimitOrder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface LimitOrderRepository extends JpaRepository<LimitOrder, Long> {

    List<LimitOrder> findByStatus(String status);

    Optional<LimitOrder> findByOrderId(String orderId);
}

