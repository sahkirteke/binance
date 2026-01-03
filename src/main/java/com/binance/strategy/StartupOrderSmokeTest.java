package com.binance.strategy;

import java.math.BigDecimal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.binance.exchange.BinanceFuturesOrderClient;
import com.binance.market.BinanceMarketClient;

import jakarta.annotation.PostConstruct;
import reactor.core.publisher.Mono;

@Component
public class StartupOrderSmokeTest {

	private static final Logger LOGGER = LoggerFactory.getLogger(StartupOrderSmokeTest.class);

	private final BinanceMarketClient marketClient;
	private final BinanceFuturesOrderClient orderClient;
	private final StrategyProperties strategyProperties;
	private final CtiLbStrategy ctiLbStrategy;

	public StartupOrderSmokeTest(BinanceMarketClient marketClient,
			BinanceFuturesOrderClient orderClient,
			StrategyProperties strategyProperties,
			CtiLbStrategy ctiLbStrategy) {
		this.marketClient = marketClient;
		this.orderClient = orderClient;
		this.strategyProperties = strategyProperties;
		this.ctiLbStrategy = ctiLbStrategy;
	}

	@PostConstruct
	public void run() {
		if (strategyProperties.active() != StrategyType.CTI_LB) {
			return;
		}
		if (!strategyProperties.enableOrders() || !strategyProperties.startupTestOrderEnabled()) {
			return;
		}
		for (String symbol : strategyProperties.resolvedTradeSymbols()) {
			marketClient.fetchMarkPrice(symbol)
					.flatMap(price -> {
						BigDecimal quantity = ctiLbStrategy.resolveQuantity(price.doubleValue());
						if (quantity == null || quantity.signum() <= 0) {
							LOGGER.warn("Startup test order skipped: invalid quantity for {}", symbol);
							return Mono.empty();
						}
						LOGGER.info("Startup test order: symbol={}, quantity={}, step={}",
								symbol,
								quantity,
								strategyProperties.quantityStep());
						return orderClient.fetchHedgeModeEnabled()
								.flatMap(hedgeMode -> openAndClose(symbol, quantity, hedgeMode));
					})
					.doOnError(error -> LOGGER.warn("Startup test order failed: {}", error.getMessage()))
					.onErrorResume(error -> Mono.empty())
					.subscribe();
		}
	}

	private Mono<Void> openAndClose(String symbol, BigDecimal quantity, boolean hedgeMode) {
		String positionSide = hedgeMode ? "LONG" : "";
		return orderClient.placeMarketOrder(symbol, "BUY", quantity, positionSide)
				.then(orderClient.placeReduceOnlyMarketOrder(symbol, "SELL", quantity, positionSide))
				.then();
	}
}
