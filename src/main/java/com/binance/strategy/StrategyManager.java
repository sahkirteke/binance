package com.binance.strategy;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

@Component
public class StrategyManager {

	private static final Logger LOGGER = LoggerFactory.getLogger(StrategyManager.class);

	private final Map<StrategyType, Strategy> strategies;
	private final StrategyProperties strategyProperties;
	private final AtomicReference<Strategy> activeStrategy = new AtomicReference<>();

	public StrategyManager(List<Strategy> strategies, StrategyProperties strategyProperties) {
		this.strategies = strategies.stream()
				.collect(Collectors.toMap(Strategy::type, Function.identity(), (left, right) -> {
					throw new IllegalStateException("Duplicate strategy registered for " + left.type());
				}));
		this.strategyProperties = strategyProperties;
	}

	@PostConstruct
	public void init() {
		start();
	}

	@PreDestroy
	public void shutdown() {
		stop();
	}

	public void start() {
		StrategyType active = strategyProperties.active();
		if (active == StrategyType.CTI_LB) {
			Strategy current = activeStrategy.getAndSet(null);
			if (current != null) {
				LOGGER.info("Stopping strategy: {}", current.type());
				current.stop();
			}
			LOGGER.info("Strategy set to CTI_LB; skipping legacy strategy startup.");
			return;
		}
		Strategy selected = strategies.get(active);
		if (selected == null) {
			throw new IllegalStateException("No strategy registered for type " + active);
		}
		Strategy current = activeStrategy.getAndSet(selected);
		if (current != null && current != selected) {
			current.stop();
		}
		LOGGER.info("Starting strategy: {}", active);
		selected.start();
	}

	public void stop() {
		Strategy current = activeStrategy.getAndSet(null);
		if (current == null) {
			return;
		}
		LOGGER.info("Stopping strategy: {}", current.type());
		current.stop();
	}
}
