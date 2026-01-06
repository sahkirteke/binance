package com.binance.strategy;

import com.binance.strategy.CtiScoreCalculator.RecReason;

	public record ScoreSignal(
			CtiDirection cti1mDir,
			CtiDirection cti5mDir,
			int hamCtiScore,
			int ctiDirScore,
			int macdScore,
			int score1m,
			int score5m,
			double cti1mValue,
			double cti1mPrev,
			Double cti5mValue,
			Double cti5mPrev,
			Double adx5m,
			double trendWeight,
			double adjustedScore,
			CtiDirection recommendation,
			CtiDirection bias,
			RecReason recReason,
			boolean adxGate,
			boolean adxReady,
			String adxGateReason,
			boolean cti5mReady,
			int cti5mBarsSeen,
			int cti5mPeriod,
			int adx5mBarsSeen,
			int adx5mPeriod,
			long t5mCloseUsed,
			long closeTime,
			boolean insufficientData) {
	}
