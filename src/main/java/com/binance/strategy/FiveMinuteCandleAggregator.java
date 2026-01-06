package com.binance.strategy;

import java.util.Optional;

public class FiveMinuteCandleAggregator {

	private static final long INTERVAL_MS = 5 * 60_000L;

	private long bucket = Long.MIN_VALUE;
	private Candle current;

	public Optional<Candle> update(Candle candle) {
		long currentBucket = candle.closeTime() / INTERVAL_MS;
		if (current == null) {
			current = candle;
			bucket = currentBucket;
			return Optional.empty();
		}
		if (currentBucket != bucket) {
			Candle completed = current;
			current = candle;
			bucket = currentBucket;
			return Optional.of(completed);
		}

		double high = Math.max(current.high(), candle.high());
		double low = Math.min(current.low(), candle.low());
		double volume = current.volume() + candle.volume();
		current = new Candle(current.open(), high, low, candle.close(), volume, candle.closeTime());
		return Optional.empty();
	}
}
