package com.binance.strategy;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

class CtiLbStrategyQualityScoreTest {

	@Test
	void qualityScoreDoesNotPenalizeMissingVolume() {
		StrategyProperties properties = buildProperties();
		CtiLbStrategy.EntryQualityEvaluation quality = CtiLbStrategy.evaluateEntryQuality(
				1,
				110.0,
				105.0,
				true,
				100.0,
				true,
				60.0,
				true,
				100.0,
				Double.NaN,
				false,
				1.0,
				true,
				1.0,
				true,
				true,
				properties);
		assertEquals(90, quality.qualityScore());
	}

	@Test
	void lowQualityAddsExtraConfirmBars() {
		StrategyProperties properties = buildProperties();
		CtiLbStrategy.EntryQualityEvaluation quality = CtiLbStrategy.evaluateEntryQuality(
				1,
				100.0,
				105.0,
				true,
				110.0,
				true,
				30.0,
				true,
				100.0,
				100.0,
				true,
				2.5,
				true,
				1.0,
				true,
				true,
				properties);
		int confirmBars = CtiLbStrategy.resolveConfirmBarsUsedDynamic(1, true, quality, properties);
		assertEquals(2, confirmBars);
		assertEquals(null, quality.blockReason());
	}

	@Test
	void extremeRsiBlockTriggersOnlyAtExtreme() {
		StrategyProperties properties = buildProperties();
		CtiLbStrategy.EntryQualityEvaluation quality = CtiLbStrategy.evaluateEntryQuality(
				1,
				100.0,
				95.0,
				true,
				90.0,
				true,
				90.0,
				true,
				100.0,
				100.0,
				true,
				1.0,
				true,
				1.0,
				true,
				true,
				properties);
		assertEquals("ENTRY_BLOCK_EXTREME_RSI", quality.blockReason());
	}

	@Test
	void extremeAtrBlockTriggersOnlyWhenVeryHigh() {
		StrategyProperties properties = buildProperties();
		CtiLbStrategy.EntryQualityEvaluation quality = CtiLbStrategy.evaluateEntryQuality(
				1,
				100.0,
				95.0,
				true,
				90.0,
				true,
				55.0,
				true,
				100.0,
				100.0,
				true,
				3.5,
				true,
				1.0,
				true,
				true,
				properties);
		assertEquals("ENTRY_BLOCK_EXTREME_ATR", quality.blockReason());
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
