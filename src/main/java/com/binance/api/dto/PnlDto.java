package com.binance.api.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record PnlDto(
		Long id,
		String symbol,
		BigDecimal realizedPnl,
		OffsetDateTime recordedAt) {
}
