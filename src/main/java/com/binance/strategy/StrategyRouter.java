package com.binance.strategy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class StrategyRouter {

	private static final Logger LOGGER = LoggerFactory.getLogger(StrategyRouter.class);

	private final StrategyProperties strategyProperties;
	private final CtiLbTrendIndicator trendIndicator;
	private final CtiLbStrategy ctiLbStrategy;

	public StrategyRouter(StrategyProperties strategyProperties,
			CtiLbTrendIndicator trendIndicator,
			CtiLbStrategy ctiLbStrategy) {
		this.strategyProperties = strategyProperties;
		this.trendIndicator = trendIndicator;
		this.ctiLbStrategy = ctiLbStrategy;
	}

	public void onClosedCandle(double close, long closeTime) {
		if (strategyProperties.active() != StrategyType.CTI_LB) {
			LOGGER.debug("Closed candle ignored (active={}, closeTime={})", strategyProperties.active(), closeTime);
			return;
		}
		TrendSignal signal = trendIndicator.onClosedCandle(close, closeTime);
		ctiLbStrategy.onTrendSignal(signal, close);
	}
}
