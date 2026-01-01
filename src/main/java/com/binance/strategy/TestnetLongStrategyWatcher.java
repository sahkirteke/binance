package com.binance.strategy;

import java.math.BigDecimal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.binance.market.BinanceMarketClient;

@Component
public class TestnetLongStrategyWatcher {

	private static final Logger LOGGER = LoggerFactory.getLogger(TestnetLongStrategyWatcher.class);

	private final BinanceMarketClient marketClient;
	private final StrategyProperties strategyProperties;
	private volatile boolean triggered;

	public TestnetLongStrategyWatcher(BinanceMarketClient marketClient, StrategyProperties strategyProperties) {
		this.marketClient = marketClient;
		this.strategyProperties = strategyProperties;
	}

	@Scheduled(fixedDelayString = "${strategy.poll-interval-ms:2000}")
	public void watchPrice() {
		if (triggered) {
			return;
		}

		marketClient.fetchMarkPrice(strategyProperties.symbol())
				.doOnNext(this::maybeTrigger)
				.doOnError(error -> LOGGER.warn("Mark price fetch failed", error))
				.subscribe();
	}

	private void maybeTrigger(BigDecimal markPrice) {
		if (triggered) {
			return;
		}

		LOGGER.info("Current mark price for {}: {}", strategyProperties.symbol(), markPrice);

		if (markPrice.compareTo(strategyProperties.targetPrice()) <= 0) {
			triggered = true;
			BigDecimal margin = strategyProperties.notionalUsd()
					.divide(BigDecimal.valueOf(strategyProperties.leverage()));
			LOGGER.info(
					"[TESTNET] Triggering simulated LONG. symbol={}, notionalUsd={}, leverage={}, marginUsd={}",
					strategyProperties.symbol(),
					strategyProperties.notionalUsd(),
					strategyProperties.leverage(),
					margin);
		}
	}
}
