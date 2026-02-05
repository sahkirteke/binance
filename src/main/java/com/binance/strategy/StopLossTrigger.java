package com.binance.strategy;

public record StopLossTrigger(
		String symbol,
		CtiDirection side,
		double triggerPrice,
		double stopPrice,
		String orderId,
		StopLossMode mode) {
}
