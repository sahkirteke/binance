package com.binance.strategy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

class CtiLbStrategyPullbackEntryTest {

	@Test
	void pullbackIgnoresLateBlockAndUsesPullbackMode() {
		StrategyProperties properties = buildProperties();
		long nowMs = 2_000_000L;
		long flipTimeMs = nowMs - ((long) properties.lateLimitMinutes() + 1L) * 60_000L;
		CtiLbStrategy.EntryFilterInputs inputs = new CtiLbStrategy.EntryFilterInputs(
				1,
				0,
				1.0,
				1.2,
				0.9,
				1.5,
				2.0,
				100.0,
				nowMs,
				flipTimeMs,
				1.0,
				1.4,
				true,
				1.2,
				true,
				50.0,
				true,
				100.0,
				true,
				1.0,
				true,
				1.0,
				true);

		CtiLbStrategy.EntryFilterState state = CtiLbStrategy.buildEntryFilterState(inputs, properties);
		CtiLbStrategy.EntryDecision decision = CtiLbStrategy.evaluateEntryDecision(
				CtiLbStrategy.PositionState.NONE,
				state,
				0,
				properties);

		assertEquals("PULLBACK", decision.entryMode());
		assertNotNull(decision.confirmedRec());
		assertTrue(decision.blockReason() == null || !"ENTRY_BLOCK_LATE".equals(decision.blockReason()));
	}

	@Test
	void chaseRiskBoostsConfirmBarsForScoreEntries() {
		StrategyProperties properties = buildProperties();
		long nowMs = 3_000_000L;
		long flipTimeMs = nowMs - 60_000L;
		CtiLbStrategy.EntryFilterInputs inputs = new CtiLbStrategy.EntryFilterInputs(
				1,
				1,
				1.0,
				1.0,
				1.2,
				1.3,
				1.3,
				100.0,
				nowMs,
				flipTimeMs,
				1.0,
				1.2,
				true,
				1.1,
				true,
				55.0,
				true,
				100.0,
				true,
				1.0,
				true,
				1.0,
				true);

		CtiLbStrategy.EntryFilterState state = CtiLbStrategy.buildEntryFilterState(inputs, properties);
		CtiLbStrategy.EntryDecision decision = CtiLbStrategy.evaluateEntryDecision(
				CtiLbStrategy.PositionState.NONE,
				state,
				0,
				properties);

		assertTrue(decision.confirmBarsUsed() >= 2);
		assertTrue(decision.scoreConfirmBoosted());
		assertNull(decision.confirmedRec());
	}

	@Test
	void pullbackRejectedWhenExtremeRsi() {
		StrategyProperties properties = buildProperties();
		long nowMs = 4_000_000L;
		long flipTimeMs = nowMs - 120_000L;
		CtiLbStrategy.EntryFilterInputs inputs = new CtiLbStrategy.EntryFilterInputs(
				1,
				0,
				1.0,
				1.2,
				0.9,
				1.5,
				2.0,
				100.0,
				nowMs,
				flipTimeMs,
				1.0,
				1.4,
				true,
				1.2,
				true,
				85.0,
				true,
				100.0,
				true,
				1.0,
				true,
				1.0,
				true);

		CtiLbStrategy.EntryFilterState state = CtiLbStrategy.buildEntryFilterState(inputs, properties);
		CtiLbStrategy.EntryDecision decision = CtiLbStrategy.evaluateEntryDecision(
				CtiLbStrategy.PositionState.NONE,
				state,
				0,
				properties);

		assertEquals("NORMAL", decision.entryMode());
		assertNull(decision.confirmedRec());
		assertEquals(0, decision.confirmCounter());
	}

	private StrategyProperties buildProperties() {
		return new StrategyProperties(
				StrategyType.CTI_LB,
				"BTCUSDT",
				"BTCUSDT",
				List.of("BTCUSDT"),
				50,
				BigDecimal.ONE,
				2,
				"LONG",
				true,
				false,
				500,
				100,
				800,
				1,
				12,
				BigDecimal.valueOf(0.25),
				BigDecimal.valueOf(0.05),
				BigDecimal.valueOf(0.05),
				BigDecimal.valueOf(0.04),
				BigDecimal.valueOf(0.02),
				BigDecimal.valueOf(0.03),
				BigDecimal.valueOf(0.03),
				BigDecimal.valueOf(0.03),
				BigDecimal.valueOf(0.92),
				BigDecimal.valueOf(0.98),
				BigDecimal.valueOf(0.98),
				180,
				450L,
				650,
				BigDecimal.valueOf(10),
				BigDecimal.valueOf(50),
				BigDecimal.valueOf(18),
				BigDecimal.valueOf(22),
				BigDecimal.ONE,
				BigDecimal.valueOf(0.01),
				BigDecimal.valueOf(100),
				3,
				BigDecimal.valueOf(50),
				true,
				30000,
				60000,
				BigDecimal.valueOf(0.085),
				BigDecimal.valueOf(0.06),
				2,
				BigDecimal.valueOf(0.002),
				BigDecimal.valueOf(10),
				BigDecimal.valueOf(12),
				10,
				2500L,
				false,
				false,
				300_000L,
				5,
				12,
				BigDecimal.valueOf(1.0),
				BigDecimal.valueOf(0.40),
				1,
				1,
				1,
				2,
				true,
				2000L,
				45,
				75,
				25,
				55,
				1.3,
				2.2,
				55,
				1,
				true,
				85,
				15,
				3.0,
				0.35,
				0.20,
				0.15,
				55,
				3,
				0.35,
				70,
				2,
				true,
				true,
				20,
				0.0015,
				60,
				1,
				40,
				true,
				0.25,
				0.15);
	}
}
