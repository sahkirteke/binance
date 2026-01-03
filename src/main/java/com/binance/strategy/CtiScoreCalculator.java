package com.binance.strategy;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class CtiScoreCalculator {

	private static final double ADX_THRESHOLD = 20.0;

	private final Map<String, CtiLbTrendIndicator> indicators = new ConcurrentHashMap<>();

	public TrendSignal updateCti(String symbol, String timeframe, double close, long closeTime) {
		String key = symbol + ":" + timeframe;
		CtiLbTrendIndicator indicator = indicators.computeIfAbsent(key, ignored -> new CtiLbTrendIndicator());
		synchronized (indicator) {
			return indicator.onClosedCandle(close, closeTime);
		}
	}

	public ScoreResult calculate(int hamScore, Double adxValue, boolean adxReady, boolean ready, CtiDirection bias) {
		if (!ready) {
			return new ScoreResult(0, 0.0, 0.0, CtiDirection.NEUTRAL, RecReason.INSUFFICIENT_DATA,
					false, adxReady, adxGateReason(adxReady, adxValue));
		}
		boolean adxGate = adxReady && adxValue != null && adxValue > ADX_THRESHOLD;
		int adxBonus = adxGate ? 1 : 0;
		double adjustedScore;
		if (hamScore == 0) {
			adjustedScore = 0.0;
		} else if (hamScore > 0) {
			adjustedScore = hamScore + adxBonus;
		} else {
			adjustedScore = hamScore - adxBonus;
		}
		double trendWeight = adxGate ? 1.0 : 0.0;
		Recommendation recommendation = resolveRecommendation(adjustedScore, bias);
		return new ScoreResult(
				adxBonus,
				trendWeight,
				adjustedScore,
				recommendation.direction(),
				recommendation.reason(),
				adxGate,
				adxReady,
				adxGateReason(adxReady, adxValue));
	}

	private Recommendation resolveRecommendation(double adjustedScore, CtiDirection bias) {
		if (adjustedScore > 0) {
			return new Recommendation(CtiDirection.LONG, RecReason.SCORE_RULES);
		}
		if (adjustedScore < 0) {
			return new Recommendation(CtiDirection.SHORT, RecReason.SCORE_RULES);
		}
		if (bias == CtiDirection.LONG || bias == CtiDirection.SHORT) {
			return new Recommendation(bias, RecReason.TIE_BREAK_BIAS);
		}
		return new Recommendation(CtiDirection.NEUTRAL, RecReason.SCORE_RULES);
	}

	private String adxGateReason(boolean adxReady, Double adxValue) {
		if (!adxReady) {
			return "ADX5M_NOT_READY";
		}
		if (adxValue != null && adxValue > ADX_THRESHOLD) {
			return "ADX5M>20";
		}
		return "ADX5M<=20";
	}

	private record Recommendation(CtiDirection direction, RecReason reason) {
	}

	public enum RecReason {
		SCORE_RULES,
		TIE_BREAK_BIAS,
		INSUFFICIENT_DATA
	}

	public record ScoreResult(
			int adxBonus,
			double trendWeight,
			double adjustedScore,
			CtiDirection recommendation,
			RecReason recReason,
			boolean adxGate,
			boolean adxReady,
			String adxGateReason) {
	}
}
