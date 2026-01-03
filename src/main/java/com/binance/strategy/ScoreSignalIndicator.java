package com.binance.strategy;

import java.util.Optional;
import java.util.OptionalDouble;

public class ScoreSignalIndicator {

	private static final int ADX_PERIOD = 14;

	private final String symbol;
	private final CtiScoreCalculator scoreCalculator;
	private final FiveMinuteCandleAggregator fiveMinuteAggregator = new FiveMinuteCandleAggregator();
	private final AdxIndicator adxIndicator = new AdxIndicator(ADX_PERIOD);
	private CtiDirection lastCti5mDir = CtiDirection.NEUTRAL;
	private Double lastCti5mValue;
	private Double lastCti5mPrev;
	private double lastAdx5m = Double.NaN;
	private boolean has5mCti;
	private boolean hasAdx;
	private int cti5mBarsSeen;
	private int adx5mBarsSeen;
	private long last5mCloseTime;
	private final int cti5mPeriod = CtiLbTrendIndicator.period();

	public ScoreSignalIndicator(String symbol, CtiScoreCalculator scoreCalculator) {
		this.symbol = symbol;
		this.scoreCalculator = scoreCalculator;
	}

	public ScoreSignal onClosedCandle(Candle candle) {
		TrendSignal cti1mSignal = scoreCalculator.updateCti(symbol, "1m", candle.close(), candle.closeTime());
		double cti1mValue = cti1mSignal.bfr();
		double cti1mPrev = cti1mSignal.bfrPrev();
		CtiDirection cti1mDir = resolveRawDirection(cti1mValue, cti1mPrev);

		Optional<Candle> fiveMinuteClosed = fiveMinuteAggregator.update(candle);
		if (fiveMinuteClosed.isPresent()) {
			Candle fiveMinute = fiveMinuteClosed.get();
			TrendSignal cti5mSignal = scoreCalculator.updateCti(symbol, "5m", fiveMinute.close(), fiveMinute.closeTime());
			lastCti5mValue = cti5mSignal.bfr();
			lastCti5mPrev = cti5mSignal.bfrPrev();
			lastCti5mDir = resolveRawDirection(lastCti5mValue, lastCti5mPrev);
			has5mCti = true;
			cti5mBarsSeen++;
			last5mCloseTime = fiveMinute.closeTime();
			OptionalDouble adx = adxIndicator.update(fiveMinute.high(), fiveMinute.low(), fiveMinute.close());
			adx5mBarsSeen++;
			if (adx.isPresent()) {
				lastAdx5m = adx.getAsDouble();
				hasAdx = true;
			}
		}

		int score5m = lastCti5mDir == CtiDirection.LONG ? 1 : lastCti5mDir == CtiDirection.SHORT ? -1 : 0;
		int score1m = cti1mDir == CtiDirection.LONG ? 1 : cti1mDir == CtiDirection.SHORT ? -1 : 0;
		int hamCtiScore = score5m + score1m;
		CtiDirection bias = resolveBias(cti1mDir, lastCti5mDir);
		boolean cti5mReady = has5mCti && cti5mBarsSeen >= cti5mPeriod;
		boolean adx5mReady = hasAdx && adx5mBarsSeen >= ADX_PERIOD + 1;
		boolean ready = cti5mReady && adx5mReady;
		CtiScoreCalculator.ScoreResult scoreResult = scoreCalculator.calculate(
				hamCtiScore,
				adx5mReady ? lastAdx5m : null,
				adx5mReady,
				ready,
				bias);

		Double bfr5mValue = cti5mReady ? lastCti5mValue : null;
		Double bfr5mPrev = cti5mReady ? lastCti5mPrev : null;
		Double adx5mValue = adx5mReady ? lastAdx5m : null;

		return new ScoreSignal(
				cti1mDir,
				lastCti5mDir,
				hamCtiScore,
				score1m,
				score5m,
				cti1mValue,
				cti1mPrev,
				bfr5mValue,
				bfr5mPrev,
				adx5mValue,
				scoreResult.adxBonus(),
				scoreResult.trendWeight(),
				scoreResult.adjustedScore(),
				scoreResult.recommendation(),
				bias,
				scoreResult.recReason(),
				scoreResult.adxGate(),
				adx5mReady,
				scoreResult.adxGateReason(),
				cti5mReady,
				cti5mBarsSeen,
				cti5mPeriod,
				adx5mBarsSeen,
				ADX_PERIOD,
				last5mCloseTime,
				candle.closeTime(),
				!ready);
	}

	private CtiDirection resolveRawDirection(double ctiValue, double ctiPrevValue) {
		int compare = java.math.BigDecimal.valueOf(ctiValue).compareTo(java.math.BigDecimal.valueOf(ctiPrevValue));
		if (compare > 0) {
			return CtiDirection.LONG;
		}
		if (compare < 0) {
			return CtiDirection.SHORT;
		}
		return CtiDirection.NEUTRAL;
	}

	private CtiDirection resolveBias(CtiDirection cti1mDir, CtiDirection cti5mDir) {
		if (cti5mDir != CtiDirection.NEUTRAL) {
			return cti5mDir;
		}
		return cti1mDir == null ? CtiDirection.NEUTRAL : cti1mDir;
	}
}
