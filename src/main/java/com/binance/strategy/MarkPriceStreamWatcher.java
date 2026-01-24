package com.binance.strategy;

import java.net.URI;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;

import com.binance.config.BinanceProperties;
import com.binance.wslogger.MarketDataHub;
import com.binance.wslogger.MarkPriceEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import reactor.core.Disposable;
import reactor.util.retry.Retry;

@Component
public class MarkPriceStreamWatcher {

	private static final Logger LOGGER = LoggerFactory.getLogger(MarkPriceStreamWatcher.class);

	private final BinanceProperties binanceProperties;
	private final StrategyProperties strategyProperties;
	private final WarmupProperties warmupProperties;
	private final TrailingPnlService trailingPnlService;
	private final ObjectMapper objectMapper;
	private final MarketDataHub marketDataHub;
	private final ReactorNettyWebSocketClient webSocketClient = new ReactorNettyWebSocketClient();
	private final AtomicReference<Disposable> subscriptionRef = new AtomicReference<>();
	private final AtomicReference<List<Disposable>> testnetSubscriptionsRef = new AtomicReference<>();
	private final AtomicBoolean warmupComplete = new AtomicBoolean(false);
	private final AtomicBoolean streamsStarted = new AtomicBoolean(false);

	public MarkPriceStreamWatcher(BinanceProperties binanceProperties,
			StrategyProperties strategyProperties,
			WarmupProperties warmupProperties,
			TrailingPnlService trailingPnlService,
			ObjectMapper objectMapper,
			MarketDataHub marketDataHub) {
		this.binanceProperties = binanceProperties;
		this.strategyProperties = strategyProperties;
		this.warmupProperties = warmupProperties;
		this.trailingPnlService = trailingPnlService;
		this.objectMapper = objectMapper;
		this.marketDataHub = marketDataHub;
	}

	@PostConstruct
	public void start() {
		if (strategyProperties.active() != StrategyType.CTI_LB) {
			LOGGER.info("Mark price stream not started (active={})", strategyProperties.active());
			return;
		}
		if (!strategyProperties.pnlTrailEnabled() && !strategyProperties.roiExitEnabled()) {
			LOGGER.info("Mark price stream not started (pnl trailing disabled and roi exit disabled).");
			return;
		}
		markWarmupComplete();
		startStreams();
	}

	public void startStreams() {
		if (strategyProperties.active() != StrategyType.CTI_LB) {
			return;
		}
		if (!strategyProperties.pnlTrailEnabled() && !strategyProperties.roiExitEnabled()) {
			return;
		}
		if (!streamsStarted.compareAndSet(false, true)) {
			return;
		}
		if (binanceProperties.useTestnet()) {
			LOGGER.info("EVENT=MARK_PRICE_STREAM_START mode=testnet symbols={}", strategyProperties.resolvedTradeSymbols().size());
			startTestnetStreams();
		} else {
			LOGGER.info("EVENT=MARK_PRICE_STREAM_START mode=combined symbols={}", strategyProperties.resolvedTradeSymbols().size());
			startCombinedStream();
		}
	}

	public void markWarmupComplete() {
		warmupComplete.set(true);
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

	private void handleMarkPriceMessage(String payload, String symbolHint) {
		try {
			JsonNode node = objectMapper.readTree(payload);
			JsonNode dataNode = node.get("data");
			JsonNode eventNode = dataNode != null && !dataNode.isNull() ? dataNode : node;
			String symbol = symbolHint != null ? symbolHint : eventNode.path("s").asText();
			double markPrice = eventNode.path("p").asDouble(Double.NaN);
			long eventTime = eventNode.path("E").asLong(System.currentTimeMillis());
			if (symbol == null || symbol.isBlank() || Double.isNaN(markPrice)) {
				return;
			}
			trailingPnlService.onMarkPrice(symbol, markPrice);
			marketDataHub.publish(new MarkPriceEvent(symbol, markPrice, eventTime));
		} catch (Exception ex) {
			LOGGER.warn("Failed to parse mark price message", ex);
		}
	}

	private void startCombinedStream() {
		List<String> streams = strategyProperties.resolvedTradeSymbols().stream()
				.map(symbol -> symbol.toLowerCase() + "@markPrice@1s")
				.toList();
		String streamPath = streams.stream().collect(Collectors.joining("/"));
		String streamBaseUrl = "wss://fstream.binance.com/stream?streams=";
		URI uri = URI.create(streamBaseUrl + streamPath);
		Disposable subscription = webSocketClient.execute(uri, session -> session.receive()
				.map(message -> message.getPayloadAsText())
				.doOnNext(payload -> handleMarkPriceMessage(payload, null))
				.then())
				.retryWhen(Retry.backoff(Long.MAX_VALUE, java.time.Duration.ofSeconds(1)))
				.subscribe(null, error -> LOGGER.warn("EVENT=MARK_PRICE_STREAM_ERROR reason={}", error.getMessage()));
		subscriptionRef.set(subscription);
		LOGGER.info("Mark price combined stream started for {}", streams);
	}

	private void startTestnetStreams() {
		String baseUrl = "wss://stream.binancefuture.com/ws/";
		List<Disposable> subscriptions = strategyProperties.resolvedTradeSymbols().stream()
				.map(symbol -> startTestnetStream(baseUrl, symbol.toLowerCase()))
				.toList();
		testnetSubscriptionsRef.set(subscriptions);
	}

	private Disposable startTestnetStream(String baseUrl, String symbol) {
		URI uri = URI.create(baseUrl + symbol + "@markPrice@1s");
		Disposable subscription = webSocketClient.execute(uri, session -> session.receive()
				.map(message -> message.getPayloadAsText())
				.doOnNext(payload -> handleMarkPriceMessage(payload, symbol.toUpperCase()))
				.then())
				.retryWhen(Retry.backoff(Long.MAX_VALUE, java.time.Duration.ofSeconds(1)))
				.subscribe(null, error -> LOGGER.warn("EVENT=MARK_PRICE_STREAM_ERROR reason={}", error.getMessage()));
		LOGGER.info("Mark price stream started for {}", symbol);
		return subscription;
	}
}
