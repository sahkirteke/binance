package com.binance.strategy;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CtiLbStrategyTrendHoldGuardTest {

	@Test
	void trendHoldActiveWhenLongAndIndicatorsAligned() {
		CtiLbStrategy.IndicatorsSnapshot snapshot = new CtiLbStrategy.IndicatorsSnapshot(
				100.0,
				true,
				99.0,
				true,
				55.0,
				true,
				25.0,
				true,
				CtiDirection.LONG);
		StrategyProperties props = TestFixtures.strategyProperties();
		boolean hold = CtiLbStrategy.shouldHoldPosition(
				CtiLbStrategy.PositionState.LONG,
				100.5,
				snapshot,
				1,
				0,
				5,
				props);
		assertTrue(hold);
	}

	@Test
	void trendHoldBlocksFlipWithoutConfirmedOpposite() {
		CtiLbStrategy.TrendHoldFlipDecision decision = CtiLbStrategy.evaluateTrendHoldFlipDecision(
				true,
				0,
				-1,
				80,
				1,
				1,
				1,
				60);
		assertFalse(decision.allowFlip());
	}

	@Test
	void trendHoldAllowsFlipWithQualityAndExtraConfirm() {
		CtiLbStrategy.TrendHoldFlipDecision decision = CtiLbStrategy.evaluateTrendHoldFlipDecision(
				true,
				-1,
				-1,
				70,
				3,
				2,
				1,
				60);
		assertTrue(decision.allowFlip());
	}

	@Test
	void tpTrailingExitsOnlyAfterDrawdown() {
		CtiLbStrategy.TpTrailingDecision active = CtiLbStrategy.evaluateTpTrailingDecision(
				true,
				0.30,
				false,
				Double.NaN,
				0.25,
				0.15);
		assertFalse(active.allowExit());

		CtiLbStrategy.TpTrailingDecision exit = CtiLbStrategy.evaluateTpTrailingDecision(
				true,
				0.10,
				active.tpTrailingActive(),
				active.maxPnlSeenPct(),
				0.25,
				0.15);
		assertTrue(exit.allowExit());
	}

	private static final class TestFixtures {
		private static StrategyProperties strategyProperties() {
			return new StrategyProperties(
					StrategyType.CTI_LB,
					"BTCUSDT",
					"BTCUSDT",
					java.util.List.of("BTCUSDT"),
					50,
					java.math.BigDecimal.ONE,
					2,
					"LONG",
					true,
					false,
					500,
					100,
					800,
					1,
					12,
					java.math.BigDecimal.valueOf(0.25),
					java.math.BigDecimal.valueOf(0.05),
					java.math.BigDecimal.valueOf(0.05),
					java.math.BigDecimal.valueOf(0.04),
					java.math.BigDecimal.valueOf(0.02),
					java.math.BigDecimal.valueOf(0.03),
					java.math.BigDecimal.valueOf(0.03),
					java.math.BigDecimal.valueOf(0.03),
					java.math.BigDecimal.valueOf(0.92),
					java.math.BigDecimal.valueOf(0.98),
					java.math.BigDecimal.valueOf(0.98),
					180,
					450L,
					650,
					java.math.BigDecimal.valueOf(10),
					java.math.BigDecimal.valueOf(50),
					java.math.BigDecimal.valueOf(18),
					java.math.BigDecimal.valueOf(22),
					java.math.BigDecimal.ONE,
					java.math.BigDecimal.valueOf(0.01),
					java.math.BigDecimal.valueOf(100),
					3,
					java.math.BigDecimal.valueOf(50),
					true,
					30000,
					60000,
					java.math.BigDecimal.valueOf(0.085),
					java.math.BigDecimal.valueOf(0.06),
					2,
					java.math.BigDecimal.valueOf(0.002),
					java.math.BigDecimal.valueOf(10),
					java.math.BigDecimal.valueOf(12),
					10,
					2500L,
					false,
					false,
					300_000L,
					5,
					12,
					java.math.BigDecimal.valueOf(1.0),
					java.math.BigDecimal.valueOf(0.40),
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
}
