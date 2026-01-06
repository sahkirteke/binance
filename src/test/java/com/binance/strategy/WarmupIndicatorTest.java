package com.binance.strategy;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class WarmupIndicatorTest {

	@Test
	void warmupFeedsBarsToReadyState() {
		ScoreSignalIndicator indicator = new ScoreSignalIndicator("BTCUSDT", new CtiScoreCalculator(), false);
		long closeTime = 1_000_000L;
		for (int i = 0; i < 21; i++) {
			Candle candle = new Candle(100.0, 101.0, 99.0, 100.5, 1200.0, closeTime + (i * 300_000L));
			indicator.warmupFiveMinuteCandle(candle);
		}
		assertTrue(indicator.isWarmupReady());
	}
}
