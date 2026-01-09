package com.binance.strategy;

import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBar;
import org.ta4j.core.BaseBarSeriesBuilder;
import org.ta4j.core.indicators.MACDIndicator;
import org.ta4j.core.indicators.SMAIndicator;
import org.ta4j.core.indicators.adx.ADXIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import java.math.BigDecimal;

public class ScoreSignalIndicator {

	private static final int ADX_PERIOD = 14;

	private final String symbol;
	private final CtiScoreCalculator scoreCalculator;
	private final boolean enableTieBreakBias;
	private final BarSeries macdSeries5m = new BaseBarSeriesBuilder().withName("macd5m").build();
	private final ClosePriceIndicator macdClose5m = new ClosePriceIndicator(macdSeries5m);
	private final MACDIndicator macdIndicator5m = new MACDIndicator(macdClose5m, 12, 26);
	private final SMAIndicator macdSignal5m = new SMAIndicator(macdIndicator5m, 9);
	private final BarSeries adxSeries = new BaseBarSeriesBuilder().withName("adx5m").build();
	private final ADXIndicator adxIndicator = new ADXIndicator(adxSeries, ADX_PERIOD);
	private final SMAIndicator adxSma10 = new SMAIndicator(adxIndicator, 10);
	private CtiDirection lastCti5mDir = CtiDirection.NEUTRAL;
	private Double lastCti5mValue;
	private Double lastCti5mPrev;
	private double lastAdx5m = Double.NaN;
	private double lastAdxExitPressure = Double.NaN;
	private double lastAdxSma10 = Double.NaN;
	private boolean lastDrop3;
	private boolean lastCrossDown;
	private boolean lastBelowSma;
	private boolean lastDeadZone;
	private double lastMacdHist = Double.NaN;
	private double lastMacdHistPrev = Double.NaN;
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

	public void onClosedOneMinuteCandle(Candle candle) {
		if (candle.closeTime() <= last1mCloseTime) {
			return;
		}
		last1mCloseTime = candle.closeTime();
		TrendSignal cti1mSignal = scoreCalculator.updateCti(symbol, "1m", candle.close(), candle.closeTime());
		double cti1mValue = cti1mSignal.bfr();
		double cti1mPrev = cti1mSignal.bfrPrev();
		CtiDirection cti1mDir = resolveRawDirection(cti1mValue, cti1mPrev);
		lastCti1mValue = cti1mValue;
		lastCti1mPrev = cti1mPrev;
		lastCti1mDir = cti1mDir;
	}

	public void warmupOneMinuteCandle(Candle candle) {
		if (candle.closeTime() <= last1mCloseTime) {
			return;
		}
		last1mCloseTime = candle.closeTime();
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

	public ScoreSignal onClosedFiveMinuteCandle(Candle candle) {
		if (candle.closeTime() <= last5mCloseTime) {
			return null;
		}
		updateFiveMinute(candle);
		return buildScoreSignal(candle.closeTime());
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
				BigDecimal.valueOf(fiveMinute.open()),
				BigDecimal.valueOf(fiveMinute.high()),
				BigDecimal.valueOf(fiveMinute.low()),
				BigDecimal.valueOf(fiveMinute.close()),
				BigDecimal.valueOf(fiveMinute.volume())));
		adx5mBarsSeen++;
		macdSeries5m.addBar(new BaseBar(java.time.Duration.ofMinutes(5),
				java.time.Instant.ofEpochMilli(fiveMinute.closeTime()).atZone(java.time.ZoneOffset.UTC),
				BigDecimal.valueOf(fiveMinute.open()),
				BigDecimal.valueOf(fiveMinute.high()),
				BigDecimal.valueOf(fiveMinute.low()),
				BigDecimal.valueOf(fiveMinute.close()),
				BigDecimal.valueOf(fiveMinute.volume())));
		int index = adxSeries.getEndIndex();
		if (adxSeries.getBarCount() >= ADX_PERIOD) {
			lastAdx5m = adxIndicator.getValue(index).doubleValue();
			hasAdx = true;
		}
		updateAdxExitPressure();
		updateMacdHistogram();
	}

	private ScoreSignal buildScoreSignal(long closeTime) {
		MacdHistColor histColor = resolveMacdHistColor(lastMacdHist, lastMacdHistPrev);
		double macdScore = resolveMacdScore(histColor);

		int score5m = lastCti5mDir == CtiDirection.LONG ? 1 : lastCti5mDir == CtiDirection.SHORT ? -1 : 0;
		int score1m = lastCti1mDir == CtiDirection.LONG ? 1 : lastCti1mDir == CtiDirection.SHORT ? -1 : 0;
		double weightedCtiScore = (score1m * 0.5) + (score5m * 0.5);
		int hamCtiScore = weightedCtiScore > 0.5 ? 1 : weightedCtiScore < -0.5 ? -1 : 0;
		double ctiScore = resolveCtiScore(lastCti5mDir);
		CtiDirection bias = resolveBias(lastCti5mDir);
		boolean cti5mReady = has5mCti && cti5mBarsSeen >= cti5mPeriod;
		boolean adx5mReady = hasAdx && adx5mBarsSeen >= ADX_PERIOD;
		boolean has5mTrend = cti5mReady && lastCti5mDir != CtiDirection.NEUTRAL;
		CtiScoreCalculator.ScoreResult scoreResult = scoreCalculator.calculate(
				ctiScore,
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
		Double adxSma10Value = adx5mReady ? lastAdxSma10 : null;
		Boolean drop3 = adx5mReady ? lastDrop3 : null;
		Boolean crossDown = adx5mReady ? lastCrossDown : null;
		Boolean belowSma = adx5mReady ? lastBelowSma : null;
		Boolean deadZone = adx5mReady ? lastDeadZone : null;
		Double adxExitPressure = adx5mReady ? lastAdxExitPressure : null;

		return new ScoreSignal(
				lastCti1mDir,
				lastCti5mDir,
				hamCtiScore,
				scoreResult.ctiDirScore(),
				ctiScore,
				macdScore,
				lastMacdHist,
				lastMacdHistPrev,
				scoreResult.coreScore(),
				score1m,
				score5m,
				lastCti1mValue,
				lastCti1mPrev,
				bfr5mValue,
				bfr5mPrev,
				adx5mValue,
				adxSma10Value,
				drop3,
				crossDown,
				belowSma,
				deadZone,
				adxExitPressure,
				histColor,
				scoreResult.trendWeight(),
				scoreResult.coreScore(),
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
				closeTime,
				!cti5mReady);
	}

	private void updateMacdHistogram() {
		if (macdSeries5m.getBarCount() < 35) {
			lastMacdHist = Double.NaN;
			lastMacdHistPrev = Double.NaN;
			return;
		}
		int index = macdSeries5m.getEndIndex();
		double macdValue = macdIndicator5m.getValue(index).doubleValue();
		double signalValue = macdSignal5m.getValue(index).doubleValue();
		if (Double.isNaN(macdValue) || Double.isNaN(signalValue)) {
			lastMacdHist = Double.NaN;
			lastMacdHistPrev = Double.NaN;
			return;
		}
		lastMacdHist = macdValue - signalValue;
		double prevMacdValue = macdIndicator5m.getValue(index - 1).doubleValue();
		double prevSignalValue = macdSignal5m.getValue(index - 1).doubleValue();
		if (Double.isNaN(prevMacdValue) || Double.isNaN(prevSignalValue)) {
			lastMacdHistPrev = Double.NaN;
			return;
		}
		lastMacdHistPrev = prevMacdValue - prevSignalValue;
	}

	private void updateAdxExitPressure() {
		if (adxSeries.getBarCount() < ADX_PERIOD + 10) {
			lastAdxExitPressure = Double.NaN;
			lastAdxSma10 = Double.NaN;
			lastDrop3 = false;
			lastCrossDown = false;
			lastBelowSma = false;
			lastDeadZone = false;
			return;
		}
		int index = adxSeries.getEndIndex();
		if (index < 3) {
			lastAdxExitPressure = Double.NaN;
			lastAdxSma10 = Double.NaN;
			lastDrop3 = false;
			lastCrossDown = false;
			lastBelowSma = false;
			lastDeadZone = false;
			return;
		}
		double adxValue = adxIndicator.getValue(index).doubleValue();
		double adxPrev = adxIndicator.getValue(index - 1).doubleValue();
		double adxPrev2 = adxIndicator.getValue(index - 2).doubleValue();
		double adxPrev3 = adxIndicator.getValue(index - 3).doubleValue();
		double sma10 = adxSma10.getValue(index).doubleValue();
		double sma10Prev = adxSma10.getValue(index - 1).doubleValue();
		if (Double.isNaN(adxValue) || Double.isNaN(adxPrev) || Double.isNaN(adxPrev2)
				|| Double.isNaN(adxPrev3) || Double.isNaN(sma10) || Double.isNaN(sma10Prev)) {
			lastAdxExitPressure = Double.NaN;
			return;
		}
		boolean drop3 = adxValue < adxPrev && adxPrev < adxPrev2 && adxPrev2 < adxPrev3;
		boolean crossDown = adxPrev >= sma10Prev && adxValue < sma10;
		boolean belowSma = adxValue < sma10;
		boolean deadZone = adxValue < 20.0;
		double pDrop = drop3 ? 0.75 : 0.0;
		double pSma = crossDown ? 1.00 : (belowSma ? 0.60 : 0.0);
		double pDead = deadZone ? 1.50 : 0.0;
		lastAdxExitPressure = ScoreMath.max(pDead, ScoreMath.max(pSma, pDrop));
		lastAdxSma10 = sma10;
		lastDrop3 = drop3;
		lastCrossDown = crossDown;
		lastBelowSma = belowSma;
		lastDeadZone = deadZone;
	}

	private MacdHistColor resolveMacdHistColor(double outHist, double outHistPrev) {
		if (Double.isNaN(outHist) || Double.isNaN(outHistPrev)) {
			return MacdHistColor.YELLOW;
		}
		if (outHist > 0 && outHist > outHistPrev) {
			return MacdHistColor.AQUA;
		}
		if (outHist > 0 && outHist < outHistPrev) {
			return MacdHistColor.BLUE;
		}
		if (outHist <= 0 && outHist < outHistPrev) {
			return MacdHistColor.RED;
		}
		if (outHist <= 0 && outHist > outHistPrev) {
			return MacdHistColor.MAROON;
		}
		return MacdHistColor.YELLOW;
	}

	private double resolveMacdScore(MacdHistColor histColor) {
		if (histColor == MacdHistColor.AQUA) {
			return 2.0;
		}
		if (histColor == MacdHistColor.BLUE) {
			return 1.0;
		}
		if (histColor == MacdHistColor.RED) {
			return -2.0;
		}
		if (histColor == MacdHistColor.MAROON) {
			return -1.0;
		}
		return 0.0;
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

	private CtiDirection resolveBias(CtiDirection cti5mDir) {
		if (cti5mDir == null) {
			return CtiDirection.NEUTRAL;
		}
		return cti5mDir;
	}

	private double resolveCtiScore(CtiDirection cti5mDir) {
		if (cti5mDir == CtiDirection.LONG) {
			return 1.0;
		}
		if (cti5mDir == CtiDirection.SHORT) {
			return -1.0;
		}
		return 0.0;
	}
}
