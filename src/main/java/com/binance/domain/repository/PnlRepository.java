package com.binance.domain.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.binance.domain.entity.PnlEntity;

public interface PnlRepository extends JpaRepository<PnlEntity, Long> {
}
