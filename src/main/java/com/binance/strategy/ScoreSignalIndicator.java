package com.binance.strategy;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.OptionalDouble;

public class ScoreSignalIndicator {

	private static final int ADX_PERIOD = 14;

	private final CtiLbTrendIndicator cti1mIndicator = new CtiLbTrendIndicator();
	private final CtiLbTrendIndicator cti5mIndicator = new CtiLbTrendIndicator();
	private final FiveMinuteCandleAggregator fiveMinuteAggregator = new FiveMinuteCandleAggregator();
	private final AdxIndicator adxIndicator = new AdxIndicator(ADX_PERIOD);
	private CtiDirection lastCti5mDir = CtiDirection.NEUTRAL;
	private double lastCti5mValue;
	private double lastCti5mPrev;
	private double lastAdx5m = Double.NaN;
	private boolean has5mCti;
	private boolean hasAdx;
	private long last5mCloseTime;
	public ScoreSignalIndicator() {
	}

	public ScoreSignal onClosedCandle(Candle candle) {
		TrendSignal cti1mSignal = cti1mIndicator.onClosedCandle(candle.close(), candle.closeTime());
		double cti1mValue = cti1mSignal.bfr();
		double cti1mPrev = cti1mSignal.bfrPrev();
		CtiDirection cti1mDir = resolveRawDirection(cti1mValue, cti1mPrev);

		Optional<Candle> fiveMinuteClosed = fiveMinuteAggregator.update(candle);
		if (fiveMinuteClosed.isPresent()) {
			Candle fiveMinute = fiveMinuteClosed.get();
			TrendSignal cti5mSignal = cti5mIndicator.onClosedCandle(fiveMinute.close(), fiveMinute.closeTime());
			lastCti5mValue = cti5mSignal.bfr();
			lastCti5mPrev = cti5mSignal.bfrPrev();
			lastCti5mDir = resolveRawDirection(lastCti5mValue, lastCti5mPrev);
			has5mCti = true;
			last5mCloseTime = fiveMinute.closeTime();
			OptionalDouble adx = adxIndicator.update(fiveMinute.high(), fiveMinute.low(), fiveMinute.close());
			if (adx.isPresent()) {
				lastAdx5m = adx.getAsDouble();
				hasAdx = true;
			}
		}

		int score5m = lastCti5mDir == CtiDirection.LONG ? 1 : lastCti5mDir == CtiDirection.SHORT ? -1 : 0;
		int score1m = cti1mDir == CtiDirection.LONG ? 1 : cti1mDir == CtiDirection.SHORT ? -1 : 0;
		int hamCtiScore = score5m + score1m;
		CtiScoreCalculator.ScoreResult scoreResult = CtiScoreCalculator.calculate(hamCtiScore, lastAdx5m);
		int adxBonus = scoreResult.adxBonus();
		double adjustedScore = scoreResult.adjustedScore();
		double trendWeight = scoreResult.trendWeight();
		CtiDirection recommendation = scoreResult.recommendation();

		boolean insufficientData = !has5mCti || !hasAdx;

		return new ScoreSignal(
				cti1mDir,
				lastCti5mDir,
				hamCtiScore,
				score1m,
				score5m,
				cti1mValue,
				cti1mPrev,
				lastCti5mValue,
				lastCti5mPrev,
				lastAdx5m,
				adxBonus,
				trendWeight,
				adjustedScore,
				recommendation,
				last5mCloseTime,
				candle.closeTime(),
				insufficientData);
	}

	private CtiDirection resolveRawDirection(double ctiValue, double ctiPrevValue) {
		int compare = BigDecimal.valueOf(ctiValue).compareTo(BigDecimal.valueOf(ctiPrevValue));
		if (compare > 0) {
			return CtiDirection.LONG;
		}
		if (compare < 0) {
			return CtiDirection.SHORT;
		}
		return CtiDirection.NEUTRAL;
	}

}
