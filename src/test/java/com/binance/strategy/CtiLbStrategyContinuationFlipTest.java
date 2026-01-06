package com.binance.strategy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CtiLbStrategyContinuationFlipTest {

	@Test
	void continuationBlocksExitForMinimumBars() {
		long startTime = 1_000_000L;
		CtiLbStrategy.ContinuationDecision decision = CtiLbStrategy.evaluateContinuationDecision(
				CtiDirection.LONG,
				1,
				1,
				60,
				false,
				0L,
				Double.NaN,
				100.0,
				startTime,
				55,
				3,
				0.35);

		assertTrue(decision.continuationActive());
		assertTrue(decision.holdApplied());
		assertEquals(1, decision.continuationBars());
	}

	@Test
	void continuationBlocksFlipDuringHoldWindow() {
		long startTime = 1_000_000L;
		CtiLbStrategy.ContinuationDecision decision = CtiLbStrategy.evaluateContinuationDecision(
				CtiDirection.LONG,
				1,
				1,
				60,
				false,
				0L,
				Double.NaN,
				100.0,
				startTime,
				55,
				3,
				0.35);

		assertTrue(decision.holdApplied());
	}

	@Test
	void retraceBeyondThresholdAllowsExit() {
		long startTime = 1_000_000L;
		long now = startTime + (3 * 60_000L);
		CtiLbStrategy.ContinuationDecision decision = CtiLbStrategy.evaluateContinuationDecision(
				CtiDirection.LONG,
				1,
				1,
				60,
				true,
				startTime,
				110.0,
				108.0,
				now,
				55,
				3,
				0.35);

		assertFalse(decision.holdApplied());
	}

	@Test
	void flipRequiresConfirmationWhenQualityIsLow() {
		CtiLbStrategy.FlipGateResult first = CtiLbStrategy.evaluateFlipGate(
				60,
				70,
				2,
				-1,
				0,
				0);
		assertFalse(first.allowFlip());
		assertEquals(1, first.flipConfirmCounter());

		CtiLbStrategy.FlipGateResult second = CtiLbStrategy.evaluateFlipGate(
				60,
				70,
				2,
				-1,
				first.flipConfirmCounter(),
				first.flipConfirmDir());
		assertTrue(second.allowFlip());
	}

	@Test
	void highQualityFlipBypassesConfirmation() {
		CtiLbStrategy.FlipGateResult result = CtiLbStrategy.evaluateFlipGate(
				80,
				70,
				2,
				1,
				0,
				0);
		assertTrue(result.allowFlip());
	}
}
