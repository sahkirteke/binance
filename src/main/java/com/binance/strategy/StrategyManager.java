package com.binance.strategy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

@Component
public class StrategyManager {

	private static final Logger LOGGER = LoggerFactory.getLogger(StrategyManager.class);

	private final StrategyProperties strategyProperties;
	private final EtcEthDepthStrategyWatcher etcEthDepthStrategyWatcher;

	public StrategyManager(StrategyProperties strategyProperties,
			EtcEthDepthStrategyWatcher etcEthDepthStrategyWatcher) {
		this.strategyProperties = strategyProperties;
		this.etcEthDepthStrategyWatcher = etcEthDepthStrategyWatcher;
	}

	@PostConstruct
	public void startActiveStrategy() {
		StrategyType active = strategyProperties.active();
		if (active == null) {
			LOGGER.warn("No active strategy configured.");
			return;
		}
		switch (active) {
			case ETC_ETH_DEPTH -> {
				LOGGER.info("Starting strategy: {}", active);
				etcEthDepthStrategyWatcher.start();
			}
			default -> LOGGER.warn("No strategy starter configured for {}", active);
		}
	}
}
