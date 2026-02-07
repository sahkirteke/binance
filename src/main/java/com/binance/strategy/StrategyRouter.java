package com.binance.strategy;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.binance.exchange.strategy.elite.EliteV1Strategy;

@Component
public class StrategyRouter {

	private static final Logger LOGGER = LoggerFactory.getLogger(StrategyRouter.class);

	private final StrategyProperties strategyProperties;
	private final CtiLbStrategy ctiLbStrategy;
	private final TimeSyncService timeSyncService;
	private final WarmupProperties warmupProperties;
	private final EliteV1Strategy eliteV1Strategy;
	private final CtiScoreCalculator scoreCalculator = new CtiScoreCalculator();
	private final Map<String, ScoreSignalIndicator> indicators = new ConcurrentHashMap<>();
	private final Map<String, Long> warmupFinishedAtMs = new ConcurrentHashMap<>();
	private static final long WARMUP_DUPLICATE_WINDOW_MS = 10_000L;
 	public StrategyRouter(StrategyProperties strategyProperties,
                          CtiLbStrategy ctiLbStrategy,
                          TimeSyncService timeSyncService,
                          WarmupProperties warmupProperties,
                          EliteV1Strategy eliteV1Strategy ) {
		this.strategyProperties = strategyProperties;
		this.ctiLbStrategy = ctiLbStrategy;
		this.timeSyncService = timeSyncService;
		this.warmupProperties = warmupProperties;
		this.eliteV1Strategy = eliteV1Strategy;
    }

	public void onClosedOneMinuteCandle(String symbol, Candle candle) {
		StrategyType active = strategyProperties.active();
		if (active == StrategyType.CTI_LB) {
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
						.ifPresent(closed5m -> handleClosedFiveMinute(symbol, closed5m));
			}
			return;
		}
		if (active == StrategyType.ELITE_V1) {
			eliteV1Strategy.onExternalClosedOneMinuteCandle(symbol, candle);
			return;
		}
		LOGGER.debug("Closed candle ignored (active={}, closeTime={})", active, candle.closeTime());
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
		if (strategyProperties.active() == StrategyType.CTI_LB) {
			resolveIndicator(symbol).warmupOneMinuteCandle(candle);
		}
	}

	public void warmupFiveMinuteCandle(String symbol, Candle candle) {
		if (strategyProperties.active() == StrategyType.CTI_LB) {
			ScoreSignalIndicator indicator = resolveIndicator(symbol);
			ScoreSignal signal = indicator.onClosedFiveMinuteCandle(candle);
			ctiLbStrategy.onWarmupFiveMinuteCandle(symbol, candle, signal);
			return;
		}
		if (strategyProperties.active() == StrategyType.ELITE_V1) {
			eliteV1Strategy.warmupFiveMinuteCandle(symbol, candle);
		}
	}

	public boolean isWarmupReady(String symbol) {
		if (strategyProperties.active() == StrategyType.ELITE_V1) {
			return eliteV1Strategy.isWarmupReady(symbol);
		}
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

	public void flushWarmup(String symbol) {
		if (strategyProperties.active() == StrategyType.ELITE_V1) {
			eliteV1Strategy.flushWarmup(symbol);
		}
	}

	public EliteV1Strategy.WarmupReadiness eliteWarmupReadiness(String symbol) {
		if (strategyProperties.active() != StrategyType.ELITE_V1) {
			return null;
		}
		return eliteV1Strategy.warmupReadiness(symbol);
	}

	public boolean needsKlines() {
		return needsKlines(strategyProperties.active());
	}

	public static boolean needsKlines(StrategyType type) {
		return type == StrategyType.CTI_LB || type == StrategyType.ELITE_V1;
	}

	public void setWarmupMode(boolean warmupMode) {
		if (strategyProperties.active() == StrategyType.CTI_LB) {
			ctiLbStrategy.setWarmupMode(warmupMode);
		} else if (strategyProperties.active() == StrategyType.ELITE_V1) {
			eliteV1Strategy.setWarmupMode(warmupMode);
		}
	}

	public void enableOrdersAfterWarmup(boolean enable) {
		if (!enable) {
			return;
		}
		if (strategyProperties.active() == StrategyType.CTI_LB) {
			ctiLbStrategy.enableOrdersAfterWarmup();
		} else if (strategyProperties.active() == StrategyType.ELITE_V1) {
			eliteV1Strategy.enableOrdersAfterWarmup();
		}
	}

	public reactor.core.publisher.Mono<Void> refreshAfterWarmup(String symbol) {
		if (strategyProperties.active() == StrategyType.CTI_LB) {
			return ctiLbStrategy.refreshAfterWarmup(symbol);
		}
		return reactor.core.publisher.Mono.empty();
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
