package com.binance.strategy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.binance.exchange.BinanceFuturesOrderClient;

import reactor.core.publisher.Mono;

@Component
public class CtiLbStrategy {

	private static final Logger LOGGER = LoggerFactory.getLogger(CtiLbStrategy.class);

	private final BinanceFuturesOrderClient orderClient;
	private final StrategyProperties strategyProperties;
	private final AtomicLong lastCloseTime = new AtomicLong(Long.MIN_VALUE);
	private final AtomicReference<PositionState> positionState = new AtomicReference<>(PositionState.FLAT);

	public CtiLbStrategy(BinanceFuturesOrderClient orderClient, StrategyProperties strategyProperties) {
		this.orderClient = orderClient;
		this.strategyProperties = strategyProperties;
	}

	public void onTrendSignal(TrendSignal signal, double close) {
		if (signal == null) {
			return;
		}
		String action = "NONE";
		long closeTime = signal.closeTime();
		if (closeTime == lastCloseTime.get()) {
			action = "SKIP_DUPLICATE";
			logSignal(signal, close, action);
			return;
		}
		lastCloseTime.set(closeTime);

		if (!signal.changed()) {
			action = "HOLD";
			logSignal(signal, close, action);
			return;
		}

		if (!strategyProperties.enableOrders()) {
			action = "DISABLED";
			logSignal(signal, close, action);
			return;
		}

		Trend trend = signal.trend();
		PositionState target = trend == Trend.LONG ? PositionState.LONG : PositionState.SHORT;
		action = "FLIP_TO_" + target;
		logSignal(signal, close, action);

		executeFlip(target, close)
				.doOnError(error -> LOGGER.warn("Failed to execute CTI LB flip to {}: {}", target, error.getMessage()))
				.subscribe();
	}

	private Mono<Void> executeFlip(PositionState target, double close) {
		PositionState current = positionState.get();
		BigDecimal quantity = resolveQuantity(close);
		if (quantity == null || quantity.signum() <= 0) {
			return Mono.empty();
		}
		return orderClient.fetchHedgeModeEnabled()
				.flatMap(hedgeMode -> closeIfNeeded(current, quantity, hedgeMode)
						.then(openPosition(target, quantity, hedgeMode)))
				.doOnNext(response -> positionState.set(target))
				.then();
	}

	private Mono<Void> closeIfNeeded(PositionState current, BigDecimal quantity, boolean hedgeMode) {
		if (current == PositionState.FLAT) {
			return Mono.empty();
		}
		String side = current == PositionState.LONG ? "SELL" : "BUY";
		String positionSide = hedgeMode ? current.name() : "";
		return orderClient.placeReduceOnlyMarketOrder(strategyProperties.tradeSymbol(), side, quantity, positionSide)
				.then();
	}

	private Mono<?> openPosition(PositionState target, BigDecimal quantity, boolean hedgeMode) {
		String side = target == PositionState.LONG ? "BUY" : "SELL";
		String positionSide = hedgeMode ? target.name() : "";
		return orderClient.placeMarketOrder(strategyProperties.tradeSymbol(), side, quantity, positionSide);
	}

	private BigDecimal resolveQuantity(double close) {
		BigDecimal notional = strategyProperties.positionNotionalUsdt();
		if (notional != null && notional.signum() > 0 && close > 0) {
			BigDecimal price = BigDecimal.valueOf(close);
			return notional.divide(price, 8, RoundingMode.DOWN);
		}
		return strategyProperties.marketQuantity();
	}

	private void logSignal(TrendSignal signal, double close, String action) {
		LOGGER.info("CTI LB signal closeTime={}, close={}, bfr={}, bfrPrev={}, trend={}, changed={}, action={}",
				signal.closeTime(),
				close,
				signal.bfr(),
				signal.bfrPrev(),
				signal.trend(),
				signal.changed(),
				action);
	}

	private enum PositionState {
		LONG,
		SHORT,
		FLAT
	}
}
