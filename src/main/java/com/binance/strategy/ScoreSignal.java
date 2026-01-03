package com.binance.strategy;

public record ScoreSignal(
		CtiDirection cti1mDir,
		CtiDirection cti5mDir,
		int hamCtiScore,
		int score1m,
		int score5m,
		double cti1mValue,
		double cti1mPrev,
		double cti5mValue,
		double cti5mPrev,
		double adx5m,
		int adxBonus,
		double trendWeight,
		double adjustedScore,
		CtiDirection recommendation,
		long t5mCloseUsed,
		long closeTime,
		boolean insufficientData) {
}
