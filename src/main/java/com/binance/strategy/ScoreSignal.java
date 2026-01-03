package com.binance.strategy;

import com.binance.strategy.CtiScoreCalculator.RecReason;

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
			CtiDirection bias,
			RecReason recReason,
			boolean adxGate,
			boolean adxReady,
			String adxGateReason,
			boolean cti5mReady,
			long t5mCloseUsed,
			long closeTime,
			boolean insufficientData) {
	}
