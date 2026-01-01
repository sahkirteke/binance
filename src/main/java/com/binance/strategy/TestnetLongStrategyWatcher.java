package com.binance.strategy;

import java.math.BigDecimal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.binance.exchange.BinanceFuturesOrderClient;
import com.binance.market.BinanceMarketClient;

@Component
public class TestnetLongStrategyWatcher {

	private static final Logger LOGGER = LoggerFactory.getLogger(TestnetLongStrategyWatcher.class);

	private final BinanceMarketClient marketClient;
	private final BinanceFuturesOrderClient orderClient;
	private final StrategyProperties strategyProperties;
	private volatile boolean triggered;

	public TestnetLongStrategyWatcher(BinanceMarketClient marketClient,
			BinanceFuturesOrderClient orderClient,
			StrategyProperties strategyProperties) {
		this.marketClient = marketClient;
		this.orderClient = orderClient;
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

		if (strategyProperties.targetPrice()
				.map(target -> markPrice.compareTo(target) <= 0)
				.orElse(true)) {
			triggered = true;
			BigDecimal margin = strategyProperties.notionalUsd()
					.divide(BigDecimal.valueOf(strategyProperties.leverage()));
			LOGGER.info(
					"[TESTNET] Triggering MARKET LONG. symbol={}, notionalUsd={}, leverage={}, marginUsd={}, quantity={}",
					strategyProperties.symbol(),
					strategyProperties.notionalUsd(),
					strategyProperties.leverage(),
					margin,
					strategyProperties.marketQuantity());
			orderClient.placeMarketOrder(strategyProperties.symbol(), "BUY", strategyProperties.marketQuantity())
					.doOnNext(response -> LOGGER.info("[TESTNET] Order placed. orderId={}, status={}",
							response.orderId(),
							response.status()))
					.doOnError(error -> LOGGER.warn("[TESTNET] Order placement failed", error))
					.subscribe();
		}
	}
}
