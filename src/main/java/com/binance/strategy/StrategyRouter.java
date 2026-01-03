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
	private final Map<String, CtiLbTrendIndicator> indicators = new ConcurrentHashMap<>();

	public StrategyRouter(StrategyProperties strategyProperties,
			CtiLbStrategy ctiLbStrategy) {
		this.strategyProperties = strategyProperties;
		this.ctiLbStrategy = ctiLbStrategy;
	}

	public void onClosedCandle(String symbol, double close, long closeTime) {
		if (strategyProperties.active() != StrategyType.CTI_LB) {
			LOGGER.debug("Closed candle ignored (active={}, closeTime={})", strategyProperties.active(), closeTime);
			return;
		}
		CtiLbTrendIndicator indicator = indicators.computeIfAbsent(symbol, ignored -> new CtiLbTrendIndicator());
		TrendSignal signal = indicator.onClosedCandle(close, closeTime);
		ctiLbStrategy.onTrendSignal(symbol, signal, close);
	}
}
