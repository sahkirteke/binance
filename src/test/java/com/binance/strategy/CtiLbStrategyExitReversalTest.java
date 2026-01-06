package com.binance.strategy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CtiLbStrategyExitReversalTest {

	@Test
	void trendAlignedTakeProfitHoldsPosition() {
		CtiLbStrategy.ExitHoldDecision decision = CtiLbStrategy.evaluateExitHoldDecision(
				false,
				false,
				true);

		assertTrue(decision.hold());
		assertEquals("HOLD_TREND_ALIGNED", decision.reason());
		assertTrue(decision.exitBlockedByTrendAligned());
	}

	@Test
	void exitAllowedAfterConfirmedReversal() {
		CtiLbStrategy.ExitReversalConfirm first = CtiLbStrategy.updateExitReversalConfirm(
				0,
				0,
				CtiLbStrategy.PositionState.LONG,
				-1,
				CtiDirection.SHORT,
				-1.0,
				1.0);
		CtiLbStrategy.ExitReversalConfirm second = CtiLbStrategy.updateExitReversalConfirm(
				first.counter(),
				first.dir(),
				CtiLbStrategy.PositionState.LONG,
				-1,
				CtiDirection.SHORT,
				-1.2,
				1.0);

		assertEquals(2, second.counter());
		assertEquals(-1, second.dir());
		assertTrue(second.counter() >= 2);
	}

	@Test
	void stopLossStillExitsWhenTrendAligned() {
		CtiLbStrategy.ExitHoldDecision decision = CtiLbStrategy.evaluateExitHoldDecision(
				true,
				false,
				true);

		assertFalse(decision.hold());
		assertNull(decision.reason());
	}
}
