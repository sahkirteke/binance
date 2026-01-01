package com.binance.api.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import com.binance.domain.enums.OrderSide;
import com.binance.domain.enums.OrderStatus;
import com.binance.domain.enums.OrderType;

public record OrderResponseDto(
		Long id,
		String symbol,
		OrderSide side,
		OrderType type,
		BigDecimal quantity,
		BigDecimal price,
		OrderStatus status,
		OffsetDateTime executedAt,
		OffsetDateTime createdAt) {
}
