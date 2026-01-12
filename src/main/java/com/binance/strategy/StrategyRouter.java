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
	private final TimeSyncService timeSyncService;
	private final WarmupProperties warmupProperties;
	private final CtiScoreCalculator scoreCalculator = new CtiScoreCalculator();
	private final Map<String, ScoreSignalIndicator> indicators = new ConcurrentHashMap<>();
	private final Map<String, Long> warmupFinishedAtMs = new ConcurrentHashMap<>();
	private static final long WARMUP_DUPLICATE_WINDOW_MS = 10_000L;
	private final MLPredictionService mlPredictionService;
	private final MLProperties mlProperties;
	public StrategyRouter(StrategyProperties strategyProperties,
                          CtiLbStrategy ctiLbStrategy,
                          TimeSyncService timeSyncService,
                          WarmupProperties warmupProperties,
						  MLPredictionService mlPredictionService,
						  MLProperties mlProperties) {
		this.strategyProperties = strategyProperties;
		this.ctiLbStrategy = ctiLbStrategy;
		this.timeSyncService = timeSyncService;
		this.warmupProperties = warmupProperties;
        this.mlPredictionService = mlPredictionService;
        this.mlProperties = mlProperties;
    }

	public void onClosedOneMinuteCandle(String symbol, Candle candle) {
		if (mlProperties.enabled()) {
			mlPredictionService.onNewCandle(symbol, candle);
		}
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
		indicator.onClosedOneMinuteCandle(candle);
		ctiLbStrategy.onClosedOneMinuteCandle(symbol, candle);
		if (shouldSyncLive(symbol)) {
			timeSyncService.recordClosedOneMinute(symbol, candle)
					.ifPresent(closed5m -> {
						handleClosedFiveMinute(symbol, closed5m);
					});
		}
	}

	public void onClosedFiveMinuteCandle(String symbol, Candle candle) {
		if (strategyProperties.active() != StrategyType.CTI_LB) {
			return;
		}
		if (shouldSyncLive(symbol)) {
			timeSyncService.recordClosedFiveMinute(symbol, candle);
		}
		handleClosedFiveMinute(symbol, candle);
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

	private boolean shouldSyncLive(String symbol) {
		if (!warmupProperties.enabled()) {
			return true;
		}
		return warmupFinishedAtMs.containsKey(symbol);
	}

	private ScoreSignalIndicator resolveIndicator(String symbol) {
		return indicators.computeIfAbsent(symbol,
				ignored -> new ScoreSignalIndicator(symbol, scoreCalculator, strategyProperties.enableTieBreakBias()));
	}

	private void handleClosedFiveMinute(String symbol, Candle candle) {
		ScoreSignalIndicator indicator = resolveIndicator(symbol);
		ScoreSignal signal = indicator.onClosedFiveMinuteCandle(candle);
		ctiLbStrategy.onClosedFiveMinuteCandle(symbol, candle);
		ctiLbStrategy.onScoreSignal(symbol, signal, candle);
	}
}
