package com.binance.strategy;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;

import com.binance.config.BinanceProperties;
import com.binance.exchange.BinanceFuturesOrderClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.annotation.PostConstruct;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

@Component
public class OrderStreamService {

	private static final Logger LOGGER = LoggerFactory.getLogger(OrderStreamService.class);
	private static final Duration OPEN_ORDERS_POLL_INTERVAL = Duration.ofSeconds(10);
	private static final Duration LISTEN_KEY_KEEPALIVE = Duration.ofMinutes(30);

	private final BinanceFuturesOrderClient orderClient;
	private final BinanceProperties properties;
	private final StrategyProperties strategyProperties;
	private final ObjectMapper objectMapper;
	private final OrderTracker orderTracker;
	private final CtiLbStrategy ctiLbStrategy;
	private final ReactorNettyWebSocketClient webSocketClient = new ReactorNettyWebSocketClient();
	private final AtomicReference<Disposable> socketSubscription = new AtomicReference<>();
	private final AtomicReference<String> listenKeyRef = new AtomicReference<>();

	public OrderStreamService(BinanceFuturesOrderClient orderClient,
			BinanceProperties properties,
			StrategyProperties strategyProperties,
			ObjectMapper objectMapper,
			OrderTracker orderTracker,
			CtiLbStrategy ctiLbStrategy) {
		this.orderClient = orderClient;
		this.properties = properties;
		this.strategyProperties = strategyProperties;
		this.objectMapper = objectMapper;
		this.orderTracker = orderTracker;
		this.ctiLbStrategy = ctiLbStrategy;
	}

	@PostConstruct
	public void start() {
		if (strategyProperties.active() == null || strategyProperties.active() != StrategyType.CTI_LB) {
			return;
		}
		startUserStream();
		startOpenOrdersPolling();
	}

	private void startUserStream() {
		orderClient.startUserDataStream()
				.doOnNext(listenKey -> {
					listenKeyRef.set(listenKey);
					Disposable subscription = connectUserStream(listenKey);
					socketSubscription.set(subscription);
				})
				.doOnError(error -> LOGGER.warn("EVENT=ORDER_STREAM_FAIL reason={}", error.getMessage()))
				.retryWhen(Retry.backoff(Long.MAX_VALUE, Duration.ofSeconds(2)))
				.subscribe();

		Flux.interval(LISTEN_KEY_KEEPALIVE)
				.flatMap(ignored -> {
					String listenKey = listenKeyRef.get();
					if (listenKey == null) {
						return Mono.empty();
					}
					return orderClient.keepAliveUserDataStream(listenKey)
							.doOnError(error -> LOGGER.warn("EVENT=ORDER_STREAM_KEEPALIVE_FAIL reason={}",
									error.getMessage()))
							.onErrorResume(error -> Mono.empty());
				})
				.subscribe();
	}

	private Disposable connectUserStream(String listenKey) {
		URI uri = URI.create(resolveUserStreamUrl(listenKey));
		return webSocketClient.execute(uri, session -> session.receive()
				.map(message -> message.getPayloadAsText())
				.doOnNext(this::handleUserEvent)
				.then())
				.retryWhen(Retry.backoff(Long.MAX_VALUE, Duration.ofSeconds(1)))
				.subscribe();
	}

	private void handleUserEvent(String payload) {
		try {
			JsonNode node = objectMapper.readTree(payload);
			String eventType = node.path("e").asText();
			if (!"ORDER_TRADE_UPDATE".equals(eventType)) {
				return;
			}
			JsonNode orderNode = node.path("o");
			OrderTracker.OrderUpdate update = new OrderTracker.OrderUpdate(
					orderNode.path("s").asText(),
					orderNode.path("i").asLong(),
					orderNode.path("X").asText(),
					orderNode.path("x").asText(),
					orderNode.path("S").asText(),
					orderNode.path("ps").asText(),
					orderNode.path("c").asText(),
					orderNode.path("R").asBoolean(false),
					orderNode.path("T").asLong(),
					orderNode.path("ap").asText(),
					orderNode.path("z").asText(),
					orderNode.path("q").asText());
			orderTracker.updateFromStream(update)
					.ifPresent(tracked -> logOrderUpdate(update, tracked));
			if ("FILLED".equals(update.status())) {
				ctiLbStrategy.syncPositionNow(update.symbol(), update.eventTime());
			}
		} catch (Exception ex) {
			LOGGER.warn("EVENT=ORDER_STREAM_PARSE_FAIL error={}", ex.getMessage());
		}
	}

	private void logOrderUpdate(OrderTracker.OrderUpdate update, OrderTracker.TrackedOrder tracked) {
		LOGGER.info("EVENT=ORDER_UPDATE symbol={} orderId={} clientOrderId={} correlationId={} status={} execType={} side={} positionSide={} origQty={} execQty={} avgPrice={}",
				update.symbol(),
				update.orderId(),
				update.clientOrderId(),
				tracked.correlationId() == null ? "NA" : tracked.correlationId(),
				update.status(),
				update.execType(),
				update.side(),
				update.positionSide(),
				update.origQty(),
				update.executedQty(),
				update.avgPrice());
	}

	private void startOpenOrdersPolling() {
		List<String> symbols = strategyProperties.resolvedTradeSymbols();
		Flux.interval(OPEN_ORDERS_POLL_INTERVAL)
				.flatMap(ignored -> Flux.fromIterable(symbols)
						.flatMap(symbol -> orderClient.fetchOpenOrders(symbol)
								.doOnNext(openOrders -> orderTracker.refreshOpenOrders(symbol, openOrders))
								.doOnNext(openOrders -> ctiLbStrategy.syncPositionNow(symbol, System.currentTimeMillis())))
						.onErrorResume(error -> {
							LOGGER.warn("EVENT=ORDER_POLL_FAIL reason={}", error.getMessage());
							return Mono.empty();
						}))
				.subscribe();
	}

	private String resolveUserStreamUrl(String listenKey) {
		if (properties.useTestnet()) {
			return "wss://stream.binancefuture.com/ws/" + listenKey;
		}
		return "wss://fstream.binance.com/ws/" + listenKey;
	}
}
