package com.binance.strategy;

import java.net.URI;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
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
	private static final String KLINE_INTERVAL_1M = "1m";
	private static final String KLINE_INTERVAL_5M = "5m";

	private final BinanceProperties binanceProperties;
	private final StrategyProperties strategyProperties;
	private final StrategyRouter strategyRouter;
	private final WarmupProperties warmupProperties;
	private final ObjectMapper objectMapper;
	private final ReactorNettyWebSocketClient webSocketClient = new ReactorNettyWebSocketClient();
	private final AtomicReference<Disposable> subscriptionRef = new AtomicReference<>();
	private final AtomicReference<List<Disposable>> testnetSubscriptionsRef = new AtomicReference<>();
	private final AtomicBoolean warmupComplete = new AtomicBoolean(false);
	private final AtomicBoolean streamsStarted = new AtomicBoolean(false);

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
		if (!strategyRouter.needsKlines()) {
			LOGGER.info("Kline stream not started (active={})", strategyProperties.active());
			return;
		}
		if (warmupProperties.enabled()) {
			LOGGER.info("Kline stream delayed until warmup completes.");
			return;
		}
		markWarmupComplete();
		startStreams();
	}

	public void startStreams() {
		if (!strategyRouter.needsKlines()) {
			return;
		}
		if (!warmupComplete.get()) {
			LOGGER.info("Kline stream start skipped (warmup not complete).");
			return;
		}
		if (!streamsStarted.compareAndSet(false, true)) {
			return;
		}
		if (binanceProperties.useTestnet()) {
			startTestnetStreams();
		} else {
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

	private void handleKlineMessage(String payload, String intervalHint) {
		try {
			KlineMessage message = parseKlineMessage(payload, intervalHint);
			if (message == null || message.event() == null || message.interval() == null) {
				return;
			}
			KlineEvent event = message.event();
			if (event == null || event.kline() == null) {
				return;
			}
			KlineEvent.Kline kline = event.kline();
			if (!kline.closed()) {
				return;
			}
			Candle candle = toCandle(kline);
			if (KLINE_INTERVAL_5M.equals(message.interval())) {
				LOGGER.debug("KLINE_5M_PARSED symbol={} v={} q={} n={} V={} Q={}",
					event.symbol(),
					candle.volume(),
					candle.quoteVolume(),
					candle.tradeCount(),
					candle.takerBuyBaseVolume(),
					candle.takerBuyQuoteVolume());
				strategyRouter.onClosedFiveMinuteCandle(event.symbol(), candle);
			} else {
				strategyRouter.onClosedOneMinuteCandle(event.symbol(), candle);
			}
		} catch (Exception ex) {
			LOGGER.warn("Failed to parse kline message", ex);
		}
	}


	private Candle toCandle(KlineEvent.Kline kline) {
		Double quoteVolume = readDoubleAccessor(kline, "quoteVolume");
		Long tradeCount = readLongAccessor(kline, "tradeCount");
		Double takerBuyBaseVolume = readDoubleAccessor(kline, "takerBuyBaseVolume");
		Double takerBuyQuoteVolume = readDoubleAccessor(kline, "takerBuyQuoteVolume");
		try {
			Constructor<Candle> fullCtor = Candle.class.getDeclaredConstructor(
					double.class,
					double.class,
					double.class,
					double.class,
					double.class,
					long.class,
					Double.class,
					Long.class,
					Double.class,
					Double.class);
			return fullCtor.newInstance(
					kline.open(),
					kline.high(),
					kline.low(),
					kline.close(),
					kline.volume(),
					kline.closeTime(),
					quoteVolume,
					tradeCount,
					takerBuyBaseVolume,
					takerBuyQuoteVolume);
		} catch (ReflectiveOperationException ex) {
			return new Candle(kline.open(), kline.high(), kline.low(), kline.close(), kline.volume(), kline.closeTime());
		}
	}

	private static Double readDoubleAccessor(Object target, String accessorName) {
		if (target == null) {
			return null;
		}
		try {
			Method method = target.getClass().getMethod(accessorName);
			Object value = method.invoke(target);
			if (value == null) {
				return null;
			}
			return value instanceof Number number ? number.doubleValue() : null;
		} catch (ReflectiveOperationException ex) {
			return null;
		}
	}

	private static Long readLongAccessor(Object target, String accessorName) {
		if (target == null) {
			return null;
		}
		try {
			Method method = target.getClass().getMethod(accessorName);
			Object value = method.invoke(target);
			if (value == null) {
				return null;
			}
			return value instanceof Number number ? number.longValue() : null;
		} catch (ReflectiveOperationException ex) {
			return null;
		}
	}

	private void startCombinedStream() {
		List<String> streams = strategyProperties.resolvedTradeSymbols().stream()
				.flatMap(symbol -> java.util.stream.Stream.of(
						symbol.toLowerCase() + "@kline_" + KLINE_INTERVAL_1M,
						symbol.toLowerCase() + "@kline_" + KLINE_INTERVAL_5M))
				.toList();
		String streamPath = streams.stream().collect(Collectors.joining("/"));
		String streamBaseUrl = "wss://fstream.binance.com/stream?streams=";
		URI uri = URI.create(streamBaseUrl + streamPath);
		Disposable subscription = webSocketClient.execute(uri, session -> session.receive()
				.map(message -> message.getPayloadAsText())
				.doOnNext(payload -> handleKlineMessage(payload, null))
				.then())
				.retryWhen(Retry.backoff(Long.MAX_VALUE, java.time.Duration.ofSeconds(1)))
				.subscribe(null, error -> LOGGER.warn("EVENT=KLINE_STREAM_ERROR mode=combined symbols={} reason={}",
						strategyProperties.resolvedTradeSymbols(),
						error.getMessage()));
		subscriptionRef.set(subscription);
		LOGGER.info("Kline combined stream started for {} interval {}", streams,
				List.of(KLINE_INTERVAL_1M, KLINE_INTERVAL_5M));
	}

	private void startTestnetStreams() {
		String baseUrl = "wss://stream.binancefuture.com/ws/";
		List<Disposable> subscriptions = new ArrayList<>();
		for (String symbol : strategyProperties.resolvedTradeSymbols()) {
			String lowerSymbol = symbol.toLowerCase();
			subscriptions.add(startTestnetStream(baseUrl, lowerSymbol, KLINE_INTERVAL_1M));
			subscriptions.add(startTestnetStream(baseUrl, lowerSymbol, KLINE_INTERVAL_5M));
		}
		testnetSubscriptionsRef.set(subscriptions);
	}

	private Disposable startTestnetStream(String baseUrl, String symbol, String interval) {
		URI uri = URI.create(baseUrl + symbol + "@kline_" + interval);
		Disposable subscription = webSocketClient.execute(uri, session -> session.receive()
				.map(message -> message.getPayloadAsText())
				.doOnNext(payload -> handleKlineMessage(payload, interval))
				.then())
				.retryWhen(Retry.backoff(Long.MAX_VALUE, java.time.Duration.ofSeconds(1)))
				.subscribe(null, error -> LOGGER.warn("EVENT=KLINE_STREAM_ERROR mode=testnet symbol={} interval={} reason={}",
						symbol.toUpperCase(),
						interval,
						error.getMessage()));
		LOGGER.info("Kline stream started for {} interval {}", symbol, interval);
		return subscription;
	}

	private KlineMessage parseKlineMessage(String payload, String intervalHint) throws Exception {
		JsonNode node = objectMapper.readTree(payload);
		String interval = intervalHint;
		JsonNode streamNode = node.get("stream");
		if (streamNode != null && !streamNode.isNull()) {
			String stream = streamNode.asText();
			if (stream != null && stream.contains("@kline_")) {
				interval = stream.substring(stream.indexOf("@kline_") + 7);
			}
		}
		JsonNode dataNode = node.get("data");
		KlineEvent event = dataNode != null && !dataNode.isNull()
				? objectMapper.treeToValue(dataNode, KlineEvent.class)
				: objectMapper.treeToValue(node, KlineEvent.class);
		return new KlineMessage(interval, event);
	}

	private record KlineMessage(
			String interval,
			KlineEvent event) {
	}
}
