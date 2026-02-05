package com.binance.strategy;

import java.math.BigDecimal;

public record StopLossOrderRequest(
		String symbol,
		CtiDirection side,
		double stopPrice,
		String workingType,
		boolean priceProtect,
		boolean closePosition,
		BigDecimal entryAvgPrice,
		double slPct,
		double stopPriceRaw,
		BigDecimal tickSize) {
}
