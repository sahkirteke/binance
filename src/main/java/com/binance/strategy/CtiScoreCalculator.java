package com.binance.strategy;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class CtiScoreCalculator {

	private static final double ADX_THRESHOLD = 25.0;

	private final Map<String, CtiLbTrendIndicator> indicators = new ConcurrentHashMap<>();

	public TrendSignal updateCti(String symbol, String timeframe, double close, long closeTime) {
		String key = symbol + ":" + timeframe;
		CtiLbTrendIndicator indicator = indicators.computeIfAbsent(key, ignored -> new CtiLbTrendIndicator());
		synchronized (indicator) {
			return indicator.onClosedCandle(close, closeTime);
		}
	}

	public ScoreResult calculate(int hamScore, int macdScore, Double adxValue, boolean adxReady, boolean ctiReady,
			boolean has5mTrend, boolean enableTieBreakBias, CtiDirection bias) {
		if (!ctiReady) {
			return new ScoreResult(0, macdScore, 0, 0.0, CtiDirection.NEUTRAL, RecReason.INSUFFICIENT_DATA,
					false, adxReady, adxGateReason(adxReady, adxValue));
		}
		boolean adxGate = adxReady && adxValue != null && adxValue > ADX_THRESHOLD;
		int ctiDirScore = Integer.compare(hamScore, 0);
		int finalScore = ctiDirScore + macdScore;
		double trendWeight = adxGate ? 1.0 : 0.0;
		boolean allowTieBreak = enableTieBreakBias && adxGate && has5mTrend && finalScore == 0;
		Recommendation recommendation = resolveRecommendation(finalScore, bias, allowTieBreak);
		return new ScoreResult(
				ctiDirScore,
				macdScore,
				finalScore,
				trendWeight,
				recommendation.direction(),
				recommendation.reason(),
				adxGate,
				adxReady,
				adxGateReason(adxReady, adxValue));
	}

	private Recommendation resolveRecommendation(int finalScore, CtiDirection bias, boolean allowTieBreak) {
		if (finalScore >= 1) {
			return new Recommendation(CtiDirection.LONG, RecReason.SCORE_RULES);
		}
		if (finalScore <= -1) {
			return new Recommendation(CtiDirection.SHORT, RecReason.SCORE_RULES);
		}
		if (allowTieBreak && (bias == CtiDirection.LONG || bias == CtiDirection.SHORT)) {
			return new Recommendation(bias, RecReason.TIE_BREAK_BIAS);
		}
		return new Recommendation(CtiDirection.NEUTRAL, RecReason.TIE_HOLD);
	}

	private String adxGateReason(boolean adxReady, Double adxValue) {
		if (!adxReady) {
			return "ADX5M_NOT_READY";
		}
		if (adxValue != null && adxValue > ADX_THRESHOLD) {
			return "ADX5M>25";
		}
		return "ADX5M<=25";
	}

	private record Recommendation(CtiDirection direction, RecReason reason) {
	}

	public enum RecReason {
		SCORE_RULES,
		TIE_BREAK_BIAS,
		TIE_HOLD,
		INSUFFICIENT_DATA
	}

	public record ScoreResult(
			int ctiDirScore,
			int macdScore,
			int finalScore,
			double trendWeight,
			CtiDirection recommendation,
			RecReason recReason,
			boolean adxGate,
			boolean adxReady,
			String adxGateReason) {
	}
}
