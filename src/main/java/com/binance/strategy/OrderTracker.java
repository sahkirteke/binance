package com.binance.strategy;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Component;

import com.binance.exchange.BinanceFuturesOrderClient;
import com.binance.exchange.dto.OrderResponse;

@Component
public class OrderTracker {

	private static final Set<String> OPEN_STATUSES = Set.of("NEW", "PARTIALLY_FILLED");

	private final Map<String, Map<Long, TrackedOrder>> ordersBySymbol = new ConcurrentHashMap<>();
	private final AtomicLong sequence = new AtomicLong(0L);

	public String nextCorrelationId(String symbol, String type) {
		long now = Instant.now().toEpochMilli();
		long seq = sequence.incrementAndGet();
		return symbol + "-" + type + "-" + now + "-" + seq;
	}

	public void registerSubmitted(String symbol, String correlationId, OrderResponse response, boolean reduceOnly) {
		if (response == null || response.orderId() == null) {
			return;
		}
		TrackedOrder tracked = new TrackedOrder(response.orderId(), response.status(), response.side(),
				correlationId, reduceOnly, System.currentTimeMillis());
		ordersBySymbol.computeIfAbsent(symbol, ignored -> new ConcurrentHashMap<>())
				.put(response.orderId(), tracked);
	}

	public Optional<TrackedOrder> updateFromStream(OrderUpdate update) {
		Map<Long, TrackedOrder> orders = ordersBySymbol.computeIfAbsent(update.symbol(), ignored -> new ConcurrentHashMap<>());
		TrackedOrder existing = orders.get(update.orderId());
		String correlationId = existing == null ? update.clientOrderId() : existing.correlationId();
		TrackedOrder updated = new TrackedOrder(update.orderId(), update.status(), update.side(),
				correlationId, update.reduceOnly(), update.eventTime());
		if (OPEN_STATUSES.contains(update.status())) {
			orders.put(update.orderId(), updated);
		} else {
			orders.remove(update.orderId());
		}
		return Optional.of(updated);
	}

	public void refreshOpenOrders(String symbol, Map<Long, BinanceFuturesOrderClient.OpenOrder> openOrders) {
		Map<Long, TrackedOrder> existing = ordersBySymbol.computeIfAbsent(symbol, ignored -> new ConcurrentHashMap<>());
		for (BinanceFuturesOrderClient.OpenOrder order : openOrders.values()) {
			TrackedOrder tracked = new TrackedOrder(order.orderId(), order.status(), order.side(),
					order.clientOrderId(), order.reduceOnly(), order.updateTime());
			existing.put(order.orderId(), tracked);
		}
		existing.keySet().removeIf(orderId -> !openOrders.containsKey(orderId));
	}

	public int openOrdersCount(String symbol) {
		return ordersBySymbol.getOrDefault(symbol, Map.of()).size();
	}

	public Optional<TrackedOrder> find(String symbol, long orderId) {
		return Optional.ofNullable(ordersBySymbol.getOrDefault(symbol, Map.of()).get(orderId));
	}

	public record TrackedOrder(
			long orderId,
			String status,
			String side,
			String correlationId,
			boolean reduceOnly,
			long updateTime) {
	}

	public record OrderUpdate(
			String symbol,
			long orderId,
			String status,
			String execType,
			String side,
			String positionSide,
			String clientOrderId,
			boolean reduceOnly,
			long eventTime,
			String avgPrice,
			String executedQty,
			String origQty) {
	}
}
