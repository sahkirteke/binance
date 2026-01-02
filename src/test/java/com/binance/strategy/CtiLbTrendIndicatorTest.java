package com.binance.strategy;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CtiLbTrendIndicatorTest {

	@Test
	void producesFiniteSignalsAndTrendChanges() {
		CtiLbTrendIndicator indicator = new CtiLbTrendIndicator();
		boolean changedSeen = false;
		long closeTime = 0L;

		for (int i = 0; i < 25; i++) {
			double close = 100.0 + i;
			TrendSignal signal = indicator.onClosedCandle(close, closeTime += 60_000L);
			assertTrue(Double.isFinite(signal.bfr()), "bfr should be finite");
			assertTrue(Double.isFinite(signal.bfrPrev()), "bfrPrev should be finite");
			changedSeen |= signal.changed();
		}

		for (int i = 0; i < 25; i++) {
			double close = 125.0 - i;
			TrendSignal signal = indicator.onClosedCandle(close, closeTime += 60_000L);
			assertTrue(Double.isFinite(signal.bfr()), "bfr should be finite");
			assertTrue(Double.isFinite(signal.bfrPrev()), "bfrPrev should be finite");
			changedSeen |= signal.changed();
		}

		assertTrue(changedSeen, "expected at least one trend change");
	}
}
