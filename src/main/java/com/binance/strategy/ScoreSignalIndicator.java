package com.binance.strategy;

import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBar;
import org.ta4j.core.BaseBarSeriesBuilder;
import org.ta4j.core.indicators.MACDIndicator;
import org.ta4j.core.indicators.SMAIndicator;
import org.ta4j.core.indicators.EMAIndicator;
import org.ta4j.core.indicators.MACDIndicator;
import org.ta4j.core.indicators.adx.ADXIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.num.DoubleNum;

public class ScoreSignalIndicator {

	private static final int ADX_PERIOD = 14;

	private final String symbol;
	private final CtiScoreCalculator scoreCalculator;
	private final boolean enableTieBreakBias;
	private final BarSeries macdSeries = new BaseBarSeriesBuilder().withName("macd1m")
			.withNumType(DoubleNum::valueOf)
			.build();
	private final ClosePriceIndicator macdClose = new ClosePriceIndicator(macdSeries);
	private final MACDIndicator macdIndicator = new MACDIndicator(macdClose, 12, 26);
	private final SMAIndicator macdSignal = new SMAIndicator(macdIndicator, 9);
	private final EMAIndicator macdSignal = new EMAIndicator(macdIndicator, 9);
	private final BarSeries adxSeries = new BaseBarSeriesBuilder().withName("adx5m")
			.withNumType(DoubleNum::valueOf)
			.build();
	private final ADXIndicator adxIndicator = new ADXIndicator(adxSeries, ADX_PERIOD);
	private CtiDirection lastCti5mDir = CtiDirection.NEUTRAL;
	private Double lastCti5mValue;
	private Double lastCti5mPrev;
	private double lastAdx5m = Double.NaN;
	private double lastCti1mValue;
	private double lastCti1mPrev;
	private CtiDirection lastCti1mDir = CtiDirection.NEUTRAL;
	private boolean has5mCti;
	private boolean hasAdx;
	private long last1mCloseTime;
	private int cti5mBarsSeen;
	private int adx5mBarsSeen;
	private long last5mCloseTime;
	private final int cti5mPeriod = CtiLbTrendIndicator.period();

	public ScoreSignalIndicator(String symbol, CtiScoreCalculator scoreCalculator, boolean enableTieBreakBias) {
		this.symbol = symbol;
		this.scoreCalculator = scoreCalculator;
		this.enableTieBreakBias = enableTieBreakBias;
	}

	public ScoreSignal onClosedOneMinuteCandle(Candle candle) {
		if (candle.closeTime() <= last1mCloseTime) {
			return null;
		}
		last1mCloseTime = candle.closeTime();
		macdSeries.addBar(new BaseBar(java.time.Duration.ofMinutes(1),
				java.time.Instant.ofEpochMilli(candle.closeTime()).atZone(java.time.ZoneOffset.UTC),
				candle.open(),
				candle.high(),
				candle.low(),
				candle.close(),
				candle.volume()));
		int macdIndex = macdSeries.getEndIndex();
		int macdScore = resolveMacdScore(macdIndex);
		TrendSignal cti1mSignal = scoreCalculator.updateCti(symbol, "1m", candle.close(), candle.closeTime());
		double cti1mValue = cti1mSignal.bfr();
		double cti1mPrev = cti1mSignal.bfrPrev();
		CtiDirection cti1mDir = resolveRawDirection(cti1mValue, cti1mPrev);
		lastCti1mValue = cti1mValue;
		lastCti1mPrev = cti1mPrev;
		lastCti1mDir = cti1mDir;

		int score5m = lastCti5mDir == CtiDirection.LONG ? 1 : lastCti5mDir == CtiDirection.SHORT ? -1 : 0;
		int score1m = cti1mDir == CtiDirection.LONG ? 1 : cti1mDir == CtiDirection.SHORT ? -1 : 0;
		int hamCtiScore = score5m + score1m;
		CtiDirection bias = resolveBias(cti1mDir, lastCti5mDir);
		boolean cti5mReady = has5mCti && cti5mBarsSeen >= cti5mPeriod;
		boolean adx5mReady = hasAdx && adx5mBarsSeen >= ADX_PERIOD;
		boolean has5mTrend = cti5mReady && lastCti5mDir != CtiDirection.NEUTRAL;
		CtiScoreCalculator.ScoreResult scoreResult = scoreCalculator.calculate(
				hamCtiScore,
				macdScore,
				adx5mReady ? lastAdx5m : null,
				adx5mReady,
				cti5mReady,
				has5mTrend,
				enableTieBreakBias,
				bias);

		Double bfr5mValue = cti5mReady ? lastCti5mValue : null;
		Double bfr5mPrev = cti5mReady ? lastCti5mPrev : null;
		Double adx5mValue = adx5mReady ? lastAdx5m : null;

		return new ScoreSignal(
				cti1mDir,
				lastCti5mDir,
				hamCtiScore,
				scoreResult.ctiDirScore(),
				scoreResult.macdScore(),
				scoreResult.finalScore(),
				score1m,
				score5m,
				cti1mValue,
				cti1mPrev,
				bfr5mValue,
				bfr5mPrev,
				adx5mValue,
				scoreResult.trendWeight(),
				scoreResult.finalScore(),
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
				!cti5mReady);
	}

	public void warmupOneMinuteCandle(Candle candle) {
		if (candle.closeTime() <= last1mCloseTime) {
			return;
		}
		last1mCloseTime = candle.closeTime();
		macdSeries.addBar(new BaseBar(java.time.Duration.ofMinutes(1),
				java.time.Instant.ofEpochMilli(candle.closeTime()).atZone(java.time.ZoneOffset.UTC),
				candle.open(),
				candle.high(),
				candle.low(),
				candle.close(),
				candle.volume()));
		TrendSignal cti1mSignal = scoreCalculator.updateCti(symbol, "1m", candle.close(), candle.closeTime());
		lastCti1mValue = cti1mSignal.bfr();
		lastCti1mPrev = cti1mSignal.bfrPrev();
		lastCti1mDir = resolveRawDirection(lastCti1mValue, lastCti1mPrev);
	}

	public void warmupFiveMinuteCandle(Candle candle) {
		if (candle.closeTime() <= last5mCloseTime) {
			return;
		}
		updateFiveMinute(candle);
	}

	public void onClosedFiveMinuteCandle(Candle candle) {
		if (candle.closeTime() <= last5mCloseTime) {
			return;
		}
		updateFiveMinute(candle);
	}

	public boolean isWarmupReady() {
		return cti5mBarsSeen >= cti5mPeriod && adx5mBarsSeen >= ADX_PERIOD;
	}

	public WarmupStatus warmupStatus() {
		boolean ctiReady = cti5mBarsSeen >= cti5mPeriod;
		boolean adxReady = adx5mBarsSeen >= ADX_PERIOD;
		return new WarmupStatus(cti5mBarsSeen, adx5mBarsSeen, ctiReady, adxReady);
	}

	public boolean isDuplicate1mClose(long closeTime) {
		return closeTime <= last1mCloseTime;
	}

	public long last1mCloseTime() {
		return last1mCloseTime;
	}

	public record WarmupStatus(
			int cti5mBarsSeen,
			int adx5mBarsSeen,
			boolean cti5mReady,
			boolean adx5mReady) {
	}

	private void updateFiveMinute(Candle fiveMinute) {
		TrendSignal cti5mSignal = scoreCalculator.updateCti(symbol, "5m", fiveMinute.close(), fiveMinute.closeTime());
		lastCti5mValue = cti5mSignal.bfr();
		lastCti5mPrev = cti5mSignal.bfrPrev();
		lastCti5mDir = resolveRawDirection(lastCti5mValue, lastCti5mPrev);
		has5mCti = true;
		cti5mBarsSeen++;
		last5mCloseTime = fiveMinute.closeTime();
		adxSeries.addBar(new BaseBar(java.time.Duration.ofMinutes(5),
				java.time.Instant.ofEpochMilli(fiveMinute.closeTime()).atZone(java.time.ZoneOffset.UTC),
				fiveMinute.open(),
				fiveMinute.high(),
				fiveMinute.low(),
				fiveMinute.close(),
				fiveMinute.volume()));
		adx5mBarsSeen++;
		int index = adxSeries.getEndIndex();
		if (adxSeries.getBarCount() >= ADX_PERIOD) {
			lastAdx5m = adxIndicator.getValue(index).doubleValue();
			hasAdx = true;
		}
	}

	private int resolveMacdScore(int index) {
		if (macdSeries.getBarCount() < 35) {
			return 0;
		}
		double macdValue = macdIndicator.getValue(index).doubleValue();
		double signalValue = macdSignal.getValue(index).doubleValue();
		if (Double.isNaN(macdValue) || Double.isNaN(signalValue)) {
			return 0;
		}
		if (macdValue > signalValue) {
			return 1;
		}
		if (macdValue < signalValue) {
			return -1;
		}
		return 0;
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
