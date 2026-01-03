package com.binance.strategy;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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
	private final Map<String, Long> lastCloseTimes = new ConcurrentHashMap<>();
	private final Map<String, PositionState> positionStates = new ConcurrentHashMap<>();

	public CtiLbStrategy(BinanceFuturesOrderClient orderClient, StrategyProperties strategyProperties) {
		this.orderClient = orderClient;
		this.strategyProperties = strategyProperties;
	}

	public void onTrendSignal(String symbol, TrendSignal signal, double close) {
		if (signal == null) {
			return;
		}
		String action = "NONE";
		long closeTime = signal.closeTime();
		Long previousCloseTime = lastCloseTimes.put(symbol, closeTime);
		if (previousCloseTime != null && previousCloseTime == closeTime) {
			action = "SKIP_DUPLICATE";
			logSignal(symbol, signal, close, action);
			return;
		}

		if (!signal.changed()) {
			action = "HOLD";
			logSignal(symbol, signal, close, action);
			return;
		}

		if (!strategyProperties.enableOrders()) {
			action = "DISABLED";
			logSignal(symbol, signal, close, action);
			return;
		}

		Trend trend = signal.trend();
		PositionState target = trend == Trend.LONG ? PositionState.LONG : PositionState.SHORT;
		action = "FLIP_TO_" + target;
		logSignal(symbol, signal, close, action);

		executeFlip(symbol, target, close)
				.doOnError(error -> LOGGER.warn("Failed to execute CTI LB flip to {}: {}", target, error.getMessage()))
				.onErrorResume(error -> Mono.empty())
				.subscribe();
	}

	private Mono<Void> executeFlip(String symbol, PositionState target, double close) {
		PositionState current = positionStates.getOrDefault(symbol, PositionState.FLAT);
		BigDecimal quantity = resolveQuantity(close);
		if (quantity == null || quantity.signum() <= 0) {
			return Mono.empty();
		}
		return orderClient.fetchHedgeModeEnabled()
				.flatMap(hedgeMode -> closeIfNeeded(symbol, current, quantity, hedgeMode)
						.then(openPosition(symbol, target, quantity, hedgeMode)))
				.doOnNext(response -> positionStates.put(symbol, target))
				.then();
	}

	private Mono<Void> closeIfNeeded(String symbol, PositionState current, BigDecimal quantity, boolean hedgeMode) {
		if (current == PositionState.FLAT) {
			return Mono.empty();
		}
		String side = current == PositionState.LONG ? "SELL" : "BUY";
		String positionSide = hedgeMode ? current.name() : "";
		return orderClient.placeReduceOnlyMarketOrder(symbol, side, quantity, positionSide)
				.then();
	}

	private Mono<?> openPosition(String symbol, PositionState target, BigDecimal quantity, boolean hedgeMode) {
		String side = target == PositionState.LONG ? "BUY" : "SELL";
		String positionSide = hedgeMode ? target.name() : "";
		return orderClient.placeMarketOrder(symbol, side, quantity, positionSide);
	}

	BigDecimal resolveQuantity(double close) {
		BigDecimal notional = strategyProperties.positionNotionalUsdt();
		if (notional != null && notional.signum() > 0 && close > 0) {
			BigDecimal price = BigDecimal.valueOf(close);
			if (strategyProperties.maxPositionUsdt() != null
					&& notional.compareTo(strategyProperties.maxPositionUsdt()) > 0) {
				notional = strategyProperties.maxPositionUsdt();
			}
			BigDecimal quantity = notional.divide(price, MathContext.DECIMAL64);
			return roundDownToStep(quantity, strategyProperties.quantityStep());
		}
		return roundDownToStep(strategyProperties.marketQuantity(), strategyProperties.quantityStep());
	}

	private BigDecimal roundDownToStep(BigDecimal value, BigDecimal step) {
		if (value == null || step == null || step.signum() <= 0) {
			return value;
		}
		BigDecimal ratio = value.divide(step, 0, RoundingMode.DOWN);
		return ratio.multiply(step, MathContext.DECIMAL64);
	}

	private void logSignal(String symbol, TrendSignal signal, double close, String action) {
		LOGGER.info("CTI LB signal symbol={}, closeTime={}, close={}, bfr={}, bfrPrev={}, trend={}, changed={}, action={}",
				symbol,
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
