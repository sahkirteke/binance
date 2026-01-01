package com.binance.domain.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.binance.domain.entity.OrderEntity;

public interface OrderRepository extends JpaRepository<OrderEntity, Long> {
}
