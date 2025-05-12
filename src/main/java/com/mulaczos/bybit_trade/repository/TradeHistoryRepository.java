package com.mulaczos.bybit_trade.repository;

import com.mulaczos.bybit_trade.model.TradeHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface TradeHistoryRepository extends JpaRepository<TradeHistory, Long> {
    Optional<TradeHistory> findFirstBySymbolAndChatTitleOrderByCreatedAtDesc(String symbol, String chatTitle);
} 