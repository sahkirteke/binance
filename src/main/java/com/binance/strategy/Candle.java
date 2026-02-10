package com.binance.strategy;

public record Candle(
		double open,
		double high,
		double low,
		double close,
		double volume,
		long closeTime,
		Double quoteVolume,
		Long tradeCount,
		Double takerBuyBaseVolume,
		Double takerBuyQuoteVolume) {

	public Candle(double open, double high, double low, double close, double volume, long closeTime) {
		this(open, high, low, close, volume, closeTime, null, null, null, null);
	}
}
