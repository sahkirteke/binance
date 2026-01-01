package com.binance.api.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import com.binance.domain.enums.OrderSide;

public record TradeDto(
		Long id,
		Long orderId,
		String symbol,
		OrderSide side,
		BigDecimal quantity,
		BigDecimal price,
		BigDecimal realizedPnl,
		OffsetDateTime executedAt) {
}
