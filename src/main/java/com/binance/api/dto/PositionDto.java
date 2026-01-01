package com.binance.api.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import com.binance.domain.enums.PositionSide;

public record PositionDto(
		Long id,
		String symbol,
		PositionSide side,
		BigDecimal quantity,
		BigDecimal entryPrice,
		BigDecimal exitPrice,
		OffsetDateTime openedAt,
		OffsetDateTime closedAt) {
}
