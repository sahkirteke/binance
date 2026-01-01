package com.binance.exchange.dto;

import java.math.BigDecimal;

public record OrderResponse(
		Long orderId,
		String symbol,
		String status,
		String side,
		String type,
		BigDecimal origQty,
		BigDecimal executedQty,
		BigDecimal avgPrice) {
}
