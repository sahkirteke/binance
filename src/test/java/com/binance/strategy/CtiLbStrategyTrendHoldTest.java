package com.binance.strategy;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

class CtiLbStrategyTrendHoldTest {

	@Test
	void holdTrendStrongWhenProfitAndOppositeWeak() {
		boolean hold = CtiLbStrategy.shouldHoldTrendStrong(
				CtiDirection.LONG,
				BigDecimal.valueOf(100.0),
				100.2,
				1,
				100.0,
				99.0,
				true,
				false,
				false,
				CtiDirection.SHORT,
				1,
				2,
				0.15);

		assertTrue(hold);
	}

	@Test
	void trailingStopHitAllowsExit() {
		boolean hold = CtiLbStrategy.shouldHoldTrendStrong(
				CtiDirection.LONG,
				BigDecimal.valueOf(100.0),
				100.2,
				1,
				100.0,
				99.0,
				true,
				true,
				false,
				CtiDirection.NEUTRAL,
				0,
				2,
				0.15);

		assertFalse(hold);
	}

	@Test
	void stopLossExitIsNeverBlocked() {
		boolean hold = CtiLbStrategy.shouldHoldTrendStrong(
				CtiDirection.LONG,
				BigDecimal.valueOf(100.0),
				95.0,
				1,
				100.0,
				99.0,
				true,
				false,
				true,
				CtiDirection.NEUTRAL,
				0,
				2,
				0.15);

		assertFalse(hold);
	}
}
