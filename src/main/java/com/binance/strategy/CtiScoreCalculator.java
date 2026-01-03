package com.binance.strategy;

public final class CtiScoreCalculator {

	private static final double ADX_THRESHOLD = 20.0;

	private CtiScoreCalculator() {
	}

	public static ScoreResult calculate(int hamScore, double adxValue) {
		int adxBonus = adxValue > ADX_THRESHOLD ? 1 : 0;
		double adjustedScore;
		if (hamScore == 0) {
			adjustedScore = 0.0;
		} else if (hamScore > 0) {
			adjustedScore = hamScore + adxBonus;
		} else {
			adjustedScore = hamScore - adxBonus;
		}
		double trendWeight = adxValue > ADX_THRESHOLD ? 1.0 : 0.0;
		CtiDirection recommendation = adjustedScore >= 2.0
				? CtiDirection.LONG
				: adjustedScore <= -2.0 ? CtiDirection.SHORT : CtiDirection.NEUTRAL;
		return new ScoreResult(adxBonus, trendWeight, adjustedScore, recommendation);
	}

	public record ScoreResult(int adxBonus, double trendWeight, double adjustedScore, CtiDirection recommendation) {
	}
}
