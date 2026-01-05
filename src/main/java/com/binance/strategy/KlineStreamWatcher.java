package com.binance.strategy;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;

import com.binance.config.BinanceProperties;
import com.binance.market.dto.KlineEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import reactor.core.Disposable;
import reactor.util.retry.Retry;

@Component
public class KlineStreamWatcher {

	private static final Logger LOGGER = LoggerFactory.getLogger(KlineStreamWatcher.class);
	private static final String KLINE_INTERVAL = "1m";

	private final BinanceProperties binanceProperties;
	private final StrategyProperties strategyProperties;
	private final StrategyRouter strategyRouter;
	private final WarmupProperties warmupProperties;
	private final ObjectMapper objectMapper;
	private final ReactorNettyWebSocketClient webSocketClient = new ReactorNettyWebSocketClient();
	private final AtomicReference<Disposable> subscriptionRef = new AtomicReference<>();
	private final AtomicReference<List<Disposable>> testnetSubscriptionsRef = new AtomicReference<>();

	public KlineStreamWatcher(BinanceProperties binanceProperties,
			StrategyProperties strategyProperties,
			StrategyRouter strategyRouter,
			WarmupProperties warmupProperties,
			ObjectMapper objectMapper) {
		this.binanceProperties = binanceProperties;
		this.strategyProperties = strategyProperties;
		this.strategyRouter = strategyRouter;
		this.warmupProperties = warmupProperties;
		this.objectMapper = objectMapper;
	}

	@PostConstruct
	public void start() {
		if (strategyProperties.active() != StrategyType.CTI_LB) {
			LOGGER.info("Kline stream not started (active={})", strategyProperties.active());
			return;
		}
		if (warmupProperties.enabled()) {
			LOGGER.info("Kline stream delayed until warmup completes.");
			return;
		}
		startStreams();
	}

	public void startStreams() {
		if (strategyProperties.active() != StrategyType.CTI_LB) {
			return;
		}
		if (binanceProperties.useTestnet()) {
			startTestnetStreams();
		} else {
			startCombinedStream();
		}
	}

	@PreDestroy
	public void stop() {
		Disposable subscription = subscriptionRef.getAndSet(null);
		if (subscription != null) {
			subscription.dispose();
		}
		List<Disposable> subscriptions = testnetSubscriptionsRef.getAndSet(null);
		if (subscriptions != null) {
			subscriptions.forEach(Disposable::dispose);
		}
	}

	private void handleKlineMessage(String payload) {
		try {
			KlineEvent event = parseKlineEvent(payload);
			if (event == null || event.kline() == null) {
				return;
			}
			KlineEvent.Kline kline = event.kline();
			if (!kline.closed()) {
				return;
			}
			Candle candle = new Candle(kline.open(), kline.high(), kline.low(), kline.close(), kline.closeTime());
			strategyRouter.onClosedCandle(event.symbol(), candle);
		} catch (Exception ex) {
			LOGGER.warn("Failed to parse kline message", ex);
		}
	}

	private void startCombinedStream() {
		List<String> streams = strategyProperties.resolvedTradeSymbols().stream()
				.map(String::toLowerCase)
				.map(symbol -> symbol + "@kline_" + KLINE_INTERVAL)
				.toList();
		String streamPath = streams.stream().collect(Collectors.joining("/"));
		String streamBaseUrl = "wss://fstream.binance.com/stream?streams=";
		URI uri = URI.create(streamBaseUrl + streamPath);
		Disposable subscription = webSocketClient.execute(uri, session -> session.receive()
				.map(message -> message.getPayloadAsText())
				.doOnNext(this::handleKlineMessage)
				.then())
				.retryWhen(Retry.backoff(Long.MAX_VALUE, java.time.Duration.ofSeconds(1)))
				.subscribe();
		subscriptionRef.set(subscription);
		LOGGER.info("Kline combined stream started for {} interval {}", streams, KLINE_INTERVAL);
	}

	private void startTestnetStreams() {
		String baseUrl = "wss://stream.binancefuture.com/ws/";
		List<Disposable> subscriptions = new ArrayList<>();
		for (String symbol : strategyProperties.resolvedTradeSymbols()) {
			String lowerSymbol = symbol.toLowerCase();
			URI uri = URI.create(baseUrl + lowerSymbol + "@kline_" + KLINE_INTERVAL);
			Disposable subscription = webSocketClient.execute(uri, session -> session.receive()
					.map(message -> message.getPayloadAsText())
					.doOnNext(this::handleKlineMessage)
					.then())
					.retryWhen(Retry.backoff(Long.MAX_VALUE, java.time.Duration.ofSeconds(1)))
					.subscribe();
			subscriptions.add(subscription);
			LOGGER.info("Kline stream started for {} interval {}", lowerSymbol, KLINE_INTERVAL);
		}
		testnetSubscriptionsRef.set(subscriptions);
	}

	private KlineEvent parseKlineEvent(String payload) throws Exception {
		JsonNode node = objectMapper.readTree(payload);
		JsonNode dataNode = node.get("data");
		if (dataNode != null && !dataNode.isNull()) {
			return objectMapper.treeToValue(dataNode, KlineEvent.class);
		}
		return objectMapper.treeToValue(node, KlineEvent.class);
	}
}
