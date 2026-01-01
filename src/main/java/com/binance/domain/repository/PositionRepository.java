package com.binance.domain.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.binance.domain.entity.PositionEntity;

public interface PositionRepository extends JpaRepository<PositionEntity, Long> {
}
