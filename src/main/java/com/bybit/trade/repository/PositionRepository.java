package com.bybit.trade.repository;

import com.bybit.trade.model.Position;
import com.bybit.trade.model.PositionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PositionRepository extends JpaRepository<Position, Long> {
    
    List<Position> findByStatus(PositionStatus status);
    
    List<Position> findBySymbol(String symbol);
    
    List<Position> findBySymbolAndStatus(String symbol, PositionStatus status);
} 