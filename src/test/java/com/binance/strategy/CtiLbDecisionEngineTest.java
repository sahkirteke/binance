package com.binance.strategy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

class CtiLbDecisionEngineTest {

	@Test
	void confirmationPendingBlocksWhenSignalExists() {
		String reason = CtiLbDecisionEngine.resolveHoldReason(true, false);
		assertEquals("BLOCK_CONFIRMATION_PENDING", reason);
	}

	@Test
	void minHoldBlocksFlipBeforeHoldWindow() {
		long now = 1_000_000L;
		CtiLbDecisionEngine.BlockDecision decision = CtiLbDecisionEngine.evaluateEntryBlocks(
				new CtiLbDecisionEngine.BlockInput(
						now,
						true,
						now - 1_000L,
						null,
						List.of(),
						60_000L,
						0L,
						0,
						100.0,
						0.1,
						0.05,
						BigDecimal.ZERO,
						BigDecimal.ZERO,
						BigDecimal.TEN));
		assertTrue(decision.blocked());
		assertEquals("BLOCK_MIN_HOLD", decision.reason());
	}

	@Test
	void cooldownBlocksReentryAfterFlip() {
		long now = 2_000_000L;
		CtiLbDecisionEngine.BlockDecision decision = CtiLbDecisionEngine.evaluateEntryBlocks(
				new CtiLbDecisionEngine.BlockInput(
						now,
						false,
						null,
						now - 10_000L,
						List.of(now - 10_000L),
						0L,
						60_000L,
						0,
						100.0,
						0.1,
						0.05,
						BigDecimal.ZERO,
						BigDecimal.ZERO,
						BigDecimal.TEN));
		assertTrue(decision.blocked());
		assertEquals("BLOCK_COOLDOWN", decision.reason());
	}

	@Test
	void stopLossAndTakeProfitTriggerExits() {
		CtiLbDecisionEngine.ExitDecision stopLoss = CtiLbDecisionEngine.evaluateExit(
				CtiDirection.LONG,
				BigDecimal.valueOf(100.0),
				98.0,
				BigDecimal.valueOf(100),
				BigDecimal.valueOf(200));
		assertTrue(stopLoss.exit());
		assertEquals("EXIT_STOP_LOSS", stopLoss.reason());

		CtiLbDecisionEngine.ExitDecision takeProfit = CtiLbDecisionEngine.evaluateExit(
				CtiDirection.SHORT,
				BigDecimal.valueOf(100.0),
				96.0,
				BigDecimal.valueOf(100),
				BigDecimal.valueOf(200));
		assertTrue(takeProfit.exit());
		assertEquals("EXIT_TAKE_PROFIT", takeProfit.reason());
	}

	@Test
	void maxFlipsPerFiveMinutesBlocksNewFlip() {
		long now = 3_000_000L;
		List<Long> flips = List.of(now - 10_000L, now - 20_000L);
		CtiLbDecisionEngine.BlockDecision decision = CtiLbDecisionEngine.evaluateEntryBlocks(
				new CtiLbDecisionEngine.BlockInput(
						now,
						true,
						now - 70_000L,
						null,
						flips,
						0L,
						0L,
						2,
						100.0,
						0.2,
						0.1,
						BigDecimal.ZERO,
						BigDecimal.ZERO,
						BigDecimal.TEN));
		assertTrue(decision.blocked());
		assertEquals("BLOCK_MAX_FLIPS", decision.reason());
	}

	@Test
	void noBlocksWhenConditionsClear() {
		long now = 4_000_000L;
		CtiLbDecisionEngine.BlockDecision decision = CtiLbDecisionEngine.evaluateEntryBlocks(
				new CtiLbDecisionEngine.BlockInput(
						now,
						true,
						now - 70_000L,
						now - 70_000L,
						List.of(now - 310_000L),
						60_000L,
						60_000L,
						2,
						100.0,
						0.2,
						0.1,
						BigDecimal.valueOf(0.05),
						BigDecimal.valueOf(10),
						BigDecimal.valueOf(99)));
		assertFalse(decision.blocked());
	}
}
