package com.binance.strategy;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class CtiScoreLogicTest {

	@Test
	void computesAdjustedScoreAndRecommendation() {
		assertResult(2, 21.0, 3.0, CtiDirection.LONG);
		assertResult(1, 21.0, 2.0, CtiDirection.LONG);
		assertResult(1, 20.0, 1.0, CtiDirection.NEUTRAL);
		assertResult(-1, 21.0, -2.0, CtiDirection.SHORT);
		assertResult(-2, 21.0, -3.0, CtiDirection.SHORT);
		assertResult(0, 21.0, 0.0, CtiDirection.NEUTRAL);
	}

	@Test
	void confirmBarsRequireConsecutiveRecommendations() {
		RecStreakTracker tracker = new RecStreakTracker();
		RecStreakTracker.RecUpdate first = tracker.update(CtiDirection.LONG, 1000L, java.math.BigDecimal.ONE, 2);
		assertEquals(1, first.streakCount());
		CtiDirection confirmedFirst = first.streakCount() >= 2 ? first.lastRec() : CtiDirection.NEUTRAL;
		assertEquals(CtiDirection.NEUTRAL, confirmedFirst);

		RecStreakTracker.RecUpdate second = tracker.update(CtiDirection.LONG, 2000L, java.math.BigDecimal.ONE, 2);
		assertEquals(2, second.streakCount());
		CtiDirection confirmedSecond = second.streakCount() >= 2 ? second.lastRec() : CtiDirection.NEUTRAL;
		assertEquals(CtiDirection.LONG, confirmedSecond);
	}

	private void assertResult(int hamScore, double adx, double expectedAdj, CtiDirection expectedRec) {
		CtiScoreCalculator.ScoreResult result = CtiScoreCalculator.calculate(hamScore, adx);
		assertEquals(expectedAdj, result.adjustedScore());
		assertEquals(expectedRec, result.recommendation());
	}
}
