package com.binance.strategy;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;

import com.binance.config.BinanceProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import reactor.core.Disposable;
import reactor.util.retry.Retry;

@Component
public class BookTickerStreamWatcher {

	private static final Logger LOGGER = LoggerFactory.getLogger(BookTickerStreamWatcher.class);

	private final BinanceProperties binanceProperties;
	private final StrategyProperties strategyProperties;
	private final ObjectMapper objectMapper;
	private final ReactorNettyWebSocketClient webSocketClient = new ReactorNettyWebSocketClient();
	private final AtomicReference<Disposable> subscriptionRef = new AtomicReference<>();
	private final AtomicReference<List<Disposable>> testnetSubscriptionsRef = new AtomicReference<>();
	private final Map<String, BookTickerSnapshot> snapshots = new ConcurrentHashMap<>();
	private final AtomicLong lastMsgMs = new AtomicLong(0L);
	private final AtomicLong msgCount = new AtomicLong(0L);
	private final ScheduledExecutorService healthExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread t = new Thread(r, "bookticker-health");
		t.setDaemon(true);
		return t;
	});
	private final AtomicReference<ScheduledFuture<?>> healthTaskRef = new AtomicReference<>();

	public BookTickerStreamWatcher(BinanceProperties binanceProperties,
			StrategyProperties strategyProperties,
			ObjectMapper objectMapper) {
		this.binanceProperties = binanceProperties;
		this.strategyProperties = strategyProperties;
		this.objectMapper = objectMapper;
	}

	@PostConstruct
	public void start() {
		if (strategyProperties.active() != StrategyType.ELITE_V1) {
			return;
		}
		LOGGER.info("BookTicker stream enabled: symbols={}", strategyProperties.resolvedTradeSymbols().size());
		startHealthLogging();
		if (binanceProperties.useTestnet()) {
			startTestnetStreams();
		} else {
			startCombinedStream();
		}
	}

	@PreDestroy
	public void stop() {
		ScheduledFuture<?> healthTask = healthTaskRef.getAndSet(null);
		if (healthTask != null) {
			healthTask.cancel(true);
		}
		healthExecutor.shutdownNow();
		Disposable subscription = subscriptionRef.getAndSet(null);
		if (subscription != null) {
			subscription.dispose();
		}
		List<Disposable> subscriptions = testnetSubscriptionsRef.getAndSet(null);
		if (subscriptions != null) {
			subscriptions.forEach(Disposable::dispose);
		}
	}

	public BookTickerSnapshot getSnapshot(String symbol) {
		if (symbol == null) {
			return null;
		}
		return snapshots.get(symbol.toUpperCase());
	}

	private void startCombinedStream() {
		List<String> streams = strategyProperties.resolvedTradeSymbols().stream()
				.map(symbol -> symbol.toLowerCase() + "@bookTicker")
				.toList();
		String streamPath = streams.stream().collect(Collectors.joining("/"));
		URI uri = URI.create("wss://fstream.binance.com/stream?streams=" + streamPath);
		Disposable subscription = webSocketClient.execute(uri, session -> session.receive()
				.map(message -> message.getPayloadAsText())
				.timeout(Duration.ofSeconds(30))
				.doOnSubscribe(ignored -> LOGGER.info("EVENT=BOOKTICKER_WS_SUBSCRIBE symbols={} mode=combined", streams.size()))
				.doOnNext(payload -> handleBookTickerMessage(payload, null))
				.doOnError(error -> LOGGER.warn("EVENT=BOOKTICKER_WS_ERROR mode=combined reason={}", error.toString()))
				.doFinally(signal -> LOGGER.warn("EVENT=BOOKTICKER_WS_FINALLY mode=combined signal={}", signal))
				.then())
				.retryWhen(Retry.backoff(Long.MAX_VALUE, Duration.ofSeconds(1))
						.maxBackoff(Duration.ofSeconds(30))
						.jitter(0.2))
				.repeat()
				.subscribe(null, error -> LOGGER.warn("EVENT=BOOK_TICKER_STREAM_ERROR reason={}", error.getMessage()));
		subscriptionRef.set(subscription);
	}

	private void startTestnetStreams() {
		String baseUrl = "wss://stream.binancefuture.com/ws/";
		List<Disposable> subscriptions = new ArrayList<>();
		for (String symbol : strategyProperties.resolvedTradeSymbols()) {
			subscriptions.add(startTestnetStream(baseUrl, symbol.toLowerCase()));
		}
		testnetSubscriptionsRef.set(subscriptions);
	}

	private Disposable startTestnetStream(String baseUrl, String symbol) {
		URI uri = URI.create(baseUrl + symbol + "@bookTicker");
		return webSocketClient.execute(uri, session -> session.receive()
				.map(message -> message.getPayloadAsText())
				.timeout(Duration.ofSeconds(30))
				.doOnSubscribe(ignored -> LOGGER.info("EVENT=BOOKTICKER_WS_SUBSCRIBE symbols=1 mode=testnet symbol={}", symbol.toUpperCase()))
				.doOnNext(payload -> handleBookTickerMessage(payload, symbol.toUpperCase()))
				.doOnError(error -> LOGGER.warn("EVENT=BOOKTICKER_WS_ERROR mode=testnet symbol={} reason={}", symbol.toUpperCase(), error.toString()))
				.doFinally(signal -> LOGGER.warn("EVENT=BOOKTICKER_WS_FINALLY mode=testnet symbol={} signal={}", symbol.toUpperCase(), signal))
				.then())
				.retryWhen(Retry.backoff(Long.MAX_VALUE, Duration.ofSeconds(1))
						.maxBackoff(Duration.ofSeconds(30))
						.jitter(0.2))
				.repeat()
				.subscribe(null, error -> LOGGER.warn("EVENT=BOOK_TICKER_STREAM_ERROR reason={}", error.getMessage()));
	}

	private void handleBookTickerMessage(String payload, String symbolHint) {
		msgCount.incrementAndGet();
		lastMsgMs.set(System.currentTimeMillis());
		try {
			JsonNode node = objectMapper.readTree(payload);
			JsonNode dataNode = node.path("data");
			JsonNode eventNode = dataNode.isMissingNode() || dataNode.isNull() ? node : dataNode;
			String symbol = symbolHint != null ? symbolHint : eventNode.path("s").asText(null);
			double bid = parseDouble(eventNode.path("b"));
			double bidQty = parseDouble(eventNode.path("B"));
			double ask = parseDouble(eventNode.path("a"));
			double askQty = parseDouble(eventNode.path("A"));
			long eventTime = eventNode.path("E").asLong(System.currentTimeMillis());
			if (symbol == null || symbol.isBlank() || !Double.isFinite(bid) || !Double.isFinite(ask)
					|| !Double.isFinite(bidQty) || !Double.isFinite(askQty)) {
				return;
			}
			snapshots.put(symbol.toUpperCase(), new BookTickerSnapshot(bid, bidQty, ask, askQty, eventTime));
		} catch (Exception ex) {
			LOGGER.warn("Failed to parse bookTicker message", ex);
		}
	}

	private void startHealthLogging() {
		ScheduledFuture<?> existing = healthTaskRef.getAndSet(null);
		if (existing != null) {
			existing.cancel(true);
		}
		ScheduledFuture<?> task = healthExecutor.scheduleAtFixedRate(() -> {
			long now = System.currentTimeMillis();
			long last = lastMsgMs.get();
			long age = last <= 0L ? -1L : Math.max(0L, now - last);
			LOGGER.info("EVENT=BOOKTICKER_HEALTH msgCount={} lastMsgAgeMs={}", msgCount.get(), age);
		}, 30, 30, TimeUnit.SECONDS);
		healthTaskRef.set(task);
	}

	private static double parseDouble(JsonNode node) {
		if (node == null || node.isMissingNode() || node.isNull()) {
			return Double.NaN;
		}
		if (node.isNumber()) {
			return node.asDouble(Double.NaN);
		}
		if (node.isTextual()) {
			try {
				return Double.parseDouble(node.asText());
			} catch (NumberFormatException ex) {
				return Double.NaN;
			}
		}
		return Double.NaN;
	}

	public record BookTickerSnapshot(double bestBidPrice,
				double bestBidQty,
				double bestAskPrice,
				double bestAskQty,
				long eventTimeMs) {
	}
}
