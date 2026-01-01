package com.binance.strategy;

import java.math.BigDecimal;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.binance.exchange.BinanceFuturesOrderClient;
import com.binance.market.BinanceMarketClient;

@Component
public class TestnetLongStrategyWatcher {

	private static final Logger LOGGER = LoggerFactory.getLogger(TestnetLongStrategyWatcher.class);
	private static final BigDecimal MIN_NOTIONAL_USD = new BigDecimal("20");

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
				.subscribe(null, error -> LOGGER.warn("Mark price stream error", error));
	}

	private void maybeTrigger(BigDecimal markPrice) {
		if (triggered) {
			return;
		}

		LOGGER.info("Current mark price for {}: {}", strategyProperties.symbol(), markPrice);

		if (Optional.ofNullable(strategyProperties.targetPrice())
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
			if (!strategyProperties.enableOrders()) {
				LOGGER.warn("[TESTNET] Order placement disabled. Set strategy.enable-orders=true to send orders.");
				return;
			}
			if (strategyProperties.notionalUsd().compareTo(MIN_NOTIONAL_USD) < 0) {
				LOGGER.warn(
						"[TESTNET] Order notionalUsd={} is below Binance minimum {}. Increase notional or use reduce-only.",
						strategyProperties.notionalUsd(),
						MIN_NOTIONAL_USD);
				return;
			}
			orderClient.placeMarketOrder(strategyProperties.symbol(),
					"BUY",
					strategyProperties.marketQuantity(),
					strategyProperties.positionSide())
					.doOnNext(response -> LOGGER.info("[TESTNET] Order placed. orderId={}, status={}",
							response.orderId(),
							response.status()))
					.doOnError(error -> LOGGER.warn("[TESTNET] Order placement failed", error))
					.subscribe(null, error -> LOGGER.warn("[TESTNET] Order stream error", error));
		}
	}
}
