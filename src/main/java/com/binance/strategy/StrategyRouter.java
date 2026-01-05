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
		ScoreSignalIndicator indicator = indicators.computeIfAbsent(symbol,
				ignored -> new ScoreSignalIndicator(symbol, scoreCalculator, strategyProperties.enableTieBreakBias()));
		ScoreSignal signal = indicator.onClosedCandle(candle);
		ctiLbStrategy.onScoreSignal(symbol, signal, candle.close());
	}
}
