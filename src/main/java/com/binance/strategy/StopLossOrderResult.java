package com.binance.strategy;

public record StopLossOrderResult(
		String orderId,
		double stopPrice) {
}
