package com.binance.strategy;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class StrategyRouter {

	private static final Logger LOGGER = LoggerFactory.getLogger(StrategyRouter.class);

	private final StrategyProperties strategyProperties;
	private final CtiLbStrategy ctiLbStrategy;
	private final CtiScoreCalculator scoreCalculator = new CtiScoreCalculator();
	private final Map<String, ScoreSignalIndicator> indicators = new ConcurrentHashMap<>();
	private final Map<String, Long> warmupFinishedAtMs = new ConcurrentHashMap<>();
	private static final long WARMUP_DUPLICATE_WINDOW_MS = 10_000L;

	public StrategyRouter(StrategyProperties strategyProperties,
			CtiLbStrategy ctiLbStrategy) {
		this.strategyProperties = strategyProperties;
		this.ctiLbStrategy = ctiLbStrategy;
	}

	public void onClosedCandle(String symbol, Candle candle) {
		if (strategyProperties.active() != StrategyType.CTI_LB) {
			LOGGER.debug("Closed candle ignored (active={}, closeTime={})",
					strategyProperties.active(),
					candle.closeTime());
			return;
		}
		ScoreSignalIndicator indicator = resolveIndicator(symbol);
		Long warmupFinishedAt = warmupFinishedAtMs.get(symbol);
		if (warmupFinishedAt != null
				&& System.currentTimeMillis() - warmupFinishedAt < WARMUP_DUPLICATE_WINDOW_MS
				&& indicator.isDuplicate1mClose(candle.closeTime())) {
			return;
		}
		ScoreSignal signal = indicator.onClosedCandle(candle);
		ctiLbStrategy.onScoreSignal(symbol, signal, candle);
	}

	public void warmupOneMinuteCandle(String symbol, Candle candle) {
		if (strategyProperties.active() != StrategyType.CTI_LB) {
			return;
		}
		resolveIndicator(symbol).warmupOneMinuteCandle(candle);
	}

	public void warmupFiveMinuteCandle(String symbol, Candle candle) {
		if (strategyProperties.active() != StrategyType.CTI_LB) {
			return;
		}
		resolveIndicator(symbol).warmupFiveMinuteCandle(candle);
		ctiLbStrategy.onWarmupFiveMinuteCandle(symbol, candle);
	}

	public boolean isWarmupReady(String symbol) {
		ScoreSignalIndicator indicator = indicators.get(symbol);
		return indicator != null && indicator.isWarmupReady();
	}

	public ScoreSignalIndicator.WarmupStatus warmupStatus(String symbol) {
		ScoreSignalIndicator indicator = indicators.get(symbol);
		return indicator == null ? null : indicator.warmupStatus();
	}

	public void markWarmupFinished(String symbol, long finishedAtMs) {
		warmupFinishedAtMs.put(symbol, finishedAtMs);
	}

	private ScoreSignalIndicator resolveIndicator(String symbol) {
		return indicators.computeIfAbsent(symbol,
				ignored -> new ScoreSignalIndicator(symbol, scoreCalculator, strategyProperties.enableTieBreakBias()));
	}
}
