package com.binance.strategy;

public record Candle(
		double open,
		double high,
		double low,
		double close,
		double volume,
		long closeTime) {
}
