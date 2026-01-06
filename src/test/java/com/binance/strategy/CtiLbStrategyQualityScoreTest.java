package com.binance.strategy;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

class CtiLbStrategyQualityScoreTest {

	@Test
	void pullbackForcesConfirmBarsOneEvenIfQualityLow() {
		StrategyProperties properties = buildProperties();
		CtiLbStrategy.QualityScoreResult quality = CtiLbStrategy.evaluateQualityScore(
				1,
				true,
				false,
				true,
				100.0,
				Double.NaN,
				Double.NaN,
				Double.NaN,
				100.0,
				Double.NaN,
				Double.NaN,
				Double.NaN,
				properties);
		int confirmBars = CtiLbStrategy.resolveConfirmBarsUsedDynamic(quality, true, properties);
		assertEquals(1, confirmBars);
	}

	@Test
	void lowQualityAddsExtraConfirmBars() {
		StrategyProperties properties = buildProperties();
		CtiLbStrategy.QualityScoreResult quality = CtiLbStrategy.evaluateQualityScore(
				1,
				true,
				true,
				false,
				100.0,
				105.0,
				110.0,
				60.0,
				100.0,
				120.0,
				5.0,
				2.0,
				properties);
		int confirmBars = CtiLbStrategy.resolveConfirmBarsUsedDynamic(quality, false, properties);
		assertEquals(2, confirmBars);
	}

	@Test
	void highQualityKeepsConfirmBarsAtOne() {
		StrategyProperties properties = buildProperties();
		CtiLbStrategy.QualityScoreResult quality = CtiLbStrategy.evaluateQualityScore(
				1,
				true,
				true,
				false,
				110.0,
				100.0,
				90.0,
				55.0,
				200.0,
				100.0,
				1.5,
				1.0,
				properties);
		int confirmBars = CtiLbStrategy.resolveConfirmBarsUsedDynamic(quality, false, properties);
		assertEquals(1, confirmBars);
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
				true,
				2000L,
				70,
				40,
				45,
				75,
				25,
				55,
				1.5,
				2.0,
				2,
				1,
				0.35,
				0.20,
				0.15);
	}
}
