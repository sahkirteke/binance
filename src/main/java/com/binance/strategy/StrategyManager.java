package com.binance.strategy;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

@Component
public class StrategyManager {

	private static final Logger LOGGER = LoggerFactory.getLogger(StrategyManager.class);

	private final StrategyProperties strategyProperties;
	private final List<StrategyRunner> strategies;

	public StrategyManager(StrategyProperties strategyProperties, List<StrategyRunner> strategies) {
		this.strategyProperties = strategyProperties;
		this.strategies = strategies;
	}

	@PostConstruct
	public void startActiveStrategy() {
		StrategyType active = strategyProperties.active();
		if (active == null || active == StrategyType.NONE) {
			LOGGER.warn("No active strategy selected. Set strategy.active to enable one.");
			return;
		}
		strategies.stream()
				.filter(strategy -> strategy.type() == active)
				.findFirst()
				.ifPresentOrElse(
						StrategyRunner::start,
						() -> LOGGER.warn("Active strategy {} not registered.", active));
	}
}
