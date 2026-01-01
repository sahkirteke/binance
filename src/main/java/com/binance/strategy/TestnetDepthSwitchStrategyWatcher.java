package com.binance.strategy;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.binance.exchange.BinanceFuturesOrderClient;
import com.binance.market.BinanceMarketClient;
import com.binance.market.dto.OrderBookDepthResponse;

import reactor.core.publisher.Mono;

@Component
public class TestnetDepthSwitchStrategyWatcher {

	private static final Logger LOGGER = LoggerFactory.getLogger(TestnetDepthSwitchStrategyWatcher.class);

	private final BinanceMarketClient marketClient;
	private final BinanceFuturesOrderClient orderClient;
	private final StrategyProperties strategyProperties;
	private final AtomicBoolean polling = new AtomicBoolean(false);
	private final AtomicReference<PositionSignal> currentPosition = new AtomicReference<>(PositionSignal.NONE);
	private final AtomicReference<PositionSignal> lastObservedSignal = new AtomicReference<>(PositionSignal.NONE);
	private final AtomicInteger consecutiveSignals = new AtomicInteger(0);
	private static final int REQUIRED_CONFIRMATIONS = 3;

	public TestnetDepthSwitchStrategyWatcher(BinanceMarketClient marketClient,
			BinanceFuturesOrderClient orderClient,
			StrategyProperties strategyProperties) {
		this.marketClient = marketClient;
		this.orderClient = orderClient;
		this.strategyProperties = strategyProperties;
	}

	@Scheduled(fixedDelayString = "${strategy.poll-interval-ms:2000}")
	public void watchDepth() {
		if (!polling.compareAndSet(false, true)) {
			return;
		}

		marketClient.fetchOrderBookDepth(strategyProperties.referenceSymbol(), strategyProperties.depthLimit())
				.flatMap(this::evaluateDepth)
				.doOnError(error -> LOGGER.warn("Depth fetch failed", error))
				.doFinally(signal -> polling.set(false))
				.subscribe(null, error -> LOGGER.warn("Depth stream error", error));
	}

	private Mono<Void> evaluateDepth(OrderBookDepthResponse depthResponse) {
		BigDecimal buyDepth = sumDepth(depthResponse.bids());
		BigDecimal sellDepth = sumDepth(depthResponse.asks());
		LOGGER.info("Depth for {} -> buy={}, sell={}", strategyProperties.referenceSymbol(), buyDepth, sellDepth);
		LOGGER.info("Depth bids (price, qty): {}", formatDepthLevels(depthResponse.bids()));
		LOGGER.info("Depth asks (price, qty): {}", formatDepthLevels(depthResponse.asks()));

		PositionSignal rawSignal = compareDepth(buyDepth, sellDepth);
		logDepthSignal(rawSignal);

		if (rawSignal == PositionSignal.NONE) {
			lastObservedSignal.set(PositionSignal.NONE);
			consecutiveSignals.set(0);
			LOGGER.info("Depth is balanced; skipping trade decision.");
			return Mono.empty();
		}

		updateConsecutiveSignal(rawSignal);
		if (consecutiveSignals.get() < REQUIRED_CONFIRMATIONS) {
			LOGGER.info("Signal {} awaiting confirmation ({}/{})", rawSignal, consecutiveSignals.get(),
					REQUIRED_CONFIRMATIONS);
			return Mono.empty();
		}

		PositionSignal desired = rawSignal;
		PositionSignal current = currentPosition.get();
		if (desired == current) {
			LOGGER.info("Desired position {} already active; no action taken.", desired);
			return Mono.empty();
		}

		return orderClient.fetchHedgeModeEnabled()
				.flatMap(hedgeMode -> executeSwitch(current, desired, hedgeMode))
				.doOnNext(success -> {
					if (Boolean.TRUE.equals(success)) {
						currentPosition.set(desired);
					}
				})
				.then()
				.onErrorResume(error -> {
					LOGGER.warn("Position switch failed", error);
					return Mono.empty();
				});
	}

	private Mono<Boolean> executeSwitch(PositionSignal current, PositionSignal desired, boolean hedgeMode) {
		LOGGER.info("Switching position from {} to {} for {}", current, desired, strategyProperties.tradeSymbol());
		if (!strategyProperties.enableOrders()) {
			LOGGER.warn("[TESTNET] Order placement disabled. Set strategy.enable-orders=true to send orders.");
			return Mono.just(false);
		}

		if (!hedgeMode) {
			return placeOrderFor(desired, "")
					.thenReturn(true);
		}

		Mono<com.binance.exchange.dto.OrderResponse> closeCurrent = Mono.empty();
		if (current == PositionSignal.LONG) {
			closeCurrent = placeOrder("SELL", strategyProperties.marketQuantity(), "LONG");
		} else if (current == PositionSignal.SHORT) {
			closeCurrent = placeOrder("BUY", strategyProperties.marketQuantity(), "SHORT");
		}

		return closeCurrent.then(placeOrderFor(desired, desired.positionSide))
				.thenReturn(true);
	}

	private Mono<Void> placeOrderFor(PositionSignal desired, String positionSide) {
		String side = desired == PositionSignal.LONG ? "BUY" : "SELL";
		return placeOrder(side, strategyProperties.marketQuantity(), positionSide).then();
	}

	private Mono<com.binance.exchange.dto.OrderResponse> placeOrder(String side, BigDecimal quantity, String positionSide) {
		LOGGER.info("[TESTNET] MARKET order: symbol={}, side={}, quantity={}, positionSide={}",
				strategyProperties.tradeSymbol(),
				side,
				quantity,
				positionSide);
		return orderClient.placeMarketOrder(strategyProperties.tradeSymbol(), side, quantity, positionSide)
				.doOnNext(response -> LOGGER.info("[TESTNET] Order placed. orderId={}, status={}",
						response.orderId(),
						response.status()));
	}

	private PositionSignal compareDepth(BigDecimal buyDepth, BigDecimal sellDepth) {
		int comparison = buyDepth.compareTo(sellDepth);
		if (comparison > 0) {
			return PositionSignal.LONG;
		}
		if (comparison < 0) {
			return PositionSignal.SHORT;
		}
		return PositionSignal.NONE;
	}

	private void updateConsecutiveSignal(PositionSignal signal) {
		PositionSignal previous = lastObservedSignal.get();
		if (signal == previous) {
			consecutiveSignals.incrementAndGet();
		} else {
			lastObservedSignal.set(signal);
			consecutiveSignals.set(1);
		}
	}

	private void logDepthSignal(PositionSignal signal) {
		if (signal == PositionSignal.LONG) {
			LOGGER.info("{} BUY", strategyProperties.referenceSymbol());
		} else if (signal == PositionSignal.SHORT) {
			LOGGER.info("{} SELL", strategyProperties.referenceSymbol());
		}
	}

	private BigDecimal sumDepth(List<List<String>> levels) {
		if (levels == null || levels.isEmpty()) {
			return BigDecimal.ZERO;
		}
		return levels.stream()
				.filter(level -> level.size() > 1)
				.map(level -> new BigDecimal(level.get(1)))
				.reduce(BigDecimal.ZERO, BigDecimal::add);
	}

	private String formatDepthLevels(List<List<String>> levels) {
		if (levels == null || levels.isEmpty()) {
			return "<empty>";
		}
		return levels.stream()
				.filter(level -> level.size() > 1)
				.map(level -> level.get(0) + "@" + level.get(1))
				.reduce((left, right) -> left + " | " + right)
				.orElse("<empty>");
	}

	private enum PositionSignal {
		LONG("LONG"),
		SHORT("SHORT"),
		NONE("");

		private final String positionSide;

		PositionSignal(String positionSide) {
			this.positionSide = positionSide;
		}
	}
}
