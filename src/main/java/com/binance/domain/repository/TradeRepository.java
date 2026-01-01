package com.binance.domain.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.binance.domain.entity.TradeEntity;

public interface TradeRepository extends JpaRepository<TradeEntity, Long> {
}
