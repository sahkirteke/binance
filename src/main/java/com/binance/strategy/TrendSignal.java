package com.binance.strategy;

public record TrendSignal(
		Trend trend,
		boolean changed,
		double bfr,
		double bfrPrev,
		long closeTime) {
}
