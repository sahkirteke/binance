package com.binance.strategy;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

class CtiLbStrategyLateBlockTest {

	@Test
	void pullbackIgnoresLateBlock() {
		CtiLbStrategy.LateBlockDecision decision = CtiLbStrategy.resolveLateBlockDecision(
				true,
				15 * 60_000L,
				12 * 60_000L,
				true,
				2.0,
				BigDecimal.valueOf(1.0));

		assertFalse(decision.lateBlocked());
		assertTrue(decision.ignoredPullback());
	}

	@Test
	void moveGateIgnoresLateBlockWhenBelowThreshold() {
		CtiLbStrategy.LateBlockDecision decision = CtiLbStrategy.resolveLateBlockDecision(
				true,
				15 * 60_000L,
				12 * 60_000L,
				false,
				0.5,
				BigDecimal.valueOf(1.0));

		assertFalse(decision.lateBlocked());
		assertTrue(decision.ignoredMoveGate());
	}

	@Test
	void lateBlockAppliesWhenMoveGatePassed() {
		CtiLbStrategy.LateBlockDecision decision = CtiLbStrategy.resolveLateBlockDecision(
				true,
				15 * 60_000L,
				12 * 60_000L,
				false,
				2.0,
				BigDecimal.valueOf(1.0));

		assertTrue(decision.lateBlocked());
	}
}
