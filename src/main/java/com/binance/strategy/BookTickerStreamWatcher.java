package com.binance.strategy;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;

import reactor.core.publisher.Flux;

import com.binance.config.BinanceProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import reactor.core.Disposable;

@Component
public class BookTickerStreamWatcher {

	private static final Logger LOGGER = LoggerFactory.getLogger(BookTickerStreamWatcher.class);

	private final BinanceProperties binanceProperties;
	private final StrategyProperties strategyProperties;
	private final ObjectMapper objectMapper;
	private final ReactorNettyWebSocketClient webSocketClient = new ReactorNettyWebSocketClient();
	private final AtomicReference<Disposable> wsRef = new AtomicReference<>();
	private final Map<String, BookTickerSnapshot> snapshots = new ConcurrentHashMap<>();
	private final AtomicLong lastMsgMs = new AtomicLong(0L);
	private final AtomicLong msgCount = new AtomicLong(0L);
	private final AtomicReference<ScheduledFuture<?>> healthTaskRef = new AtomicReference<>();
	private final AtomicBoolean running = new AtomicBoolean(false);
	private final AtomicBoolean reconnecting = new AtomicBoolean(false);
	private final AtomicInteger reconnectAttempt = new AtomicInteger(0);
	private final AtomicLong connIdSeq = new AtomicLong(0L);
	private final AtomicLong currentConnectionId = new AtomicLong(0L);
	private volatile ScheduledExecutorService healthExec;

	public BookTickerStreamWatcher(BinanceProperties binanceProperties,
			StrategyProperties strategyProperties,
			ObjectMapper objectMapper) {
		this.binanceProperties = binanceProperties;
		this.strategyProperties = strategyProperties;
		this.objectMapper = objectMapper;
	}

	@PostConstruct
	public synchronized void start() {
		if (strategyProperties.active() != StrategyType.ELITE_V1) {
			return;
		}
		running.set(true);
		Disposable existing = wsRef.get();
		if (existing != null && !existing.isDisposed()) {
			startHealthLogging();
			return;
		}
		LOGGER.info("BookTicker stream enabled: symbols={}", strategyProperties.resolvedTradeSymbols().size());
		startHealthLogging();
		subscribe("START");
	}

	@PreDestroy
	public synchronized void stop() {
		running.set(false);
		ScheduledFuture<?> healthTask = healthTaskRef.getAndSet(null);
		if (healthTask != null) {
			healthTask.cancel(false);
		}
		disposeActiveWs("STOP", "MANUAL");
	}

	public synchronized void restart(String callerTag) {
		running.set(true);
		startHealthLogging();
		disposeActiveWs("RESTART", callerTag == null || callerTag.isBlank() ? "MANUAL" : callerTag);
		subscribe("RESTART_" + (callerTag == null || callerTag.isBlank() ? "MANUAL" : callerTag));
	}

	public BookTickerSnapshot getSnapshot(String symbol) {
		if (symbol == null) {
			return null;
		}
		return snapshots.get(symbol.toUpperCase());
	}

	private void subscribe(String reason) {
		if (binanceProperties.useTestnet()) {
			startTestnetStream(reason);
		} else {
			startCombinedStream(reason);
		}
	}

	private void startCombinedStream(String reason) {
		List<String> streams = strategyProperties.resolvedTradeSymbols().stream()
				.map(symbol -> symbol.toLowerCase() + "@bookTicker")
				.toList();
		String streamPath = streams.stream().collect(Collectors.joining("/"));
		URI uri = URI.create("wss://fstream.binance.com/stream?streams=" + streamPath);
		long connId = connIdSeq.incrementAndGet();
		currentConnectionId.set(connId);
		Disposable disposable = webSocketClient.execute(uri, session -> {
			Flux<String> payloads = receivePayloads(session, null, connId, "combined");
			return payloads.then();
		})
				.doOnSubscribe(ignored -> LOGGER.info("EVENT=BOOKTICKER_WS_SUBSCRIBE symbols={} mode=combined connId={} reason={}", streams.size(), connId, reason))
				.doOnError(error -> LOGGER.warn("EVENT=BOOKTICKER_WS_ERROR mode=combined connId={} reason={}", connId, error.toString()))
				.doFinally(signal -> {
					LOGGER.warn("EVENT=BOOKTICKER_WS_FINALLY mode=combined connId={} signal={}", connId, signal);
					scheduleReconnect("FINALLY_" + signal.name());
				})
				.subscribe(null, error -> LOGGER.warn("EVENT=BOOK_TICKER_STREAM_ERROR connId={} reason={}", connId, error.getMessage()));
		wsRef.set(disposable);
	}

	private void startTestnetStream(String reason) {
		String symbol = strategyProperties.resolvedTradeSymbols().isEmpty() ? null : strategyProperties.resolvedTradeSymbols().get(0);
		if (symbol == null) {
			LOGGER.warn("EVENT=BOOKTICKER_WS_ERROR mode=testnet reason=NO_SYMBOL");
			return;
		}
		String symbolLower = symbol.toLowerCase();
		URI uri = URI.create("wss://stream.binancefuture.com/ws/" + symbolLower + "@bookTicker");
		long connId = connIdSeq.incrementAndGet();
		currentConnectionId.set(connId);
		Disposable disposable = webSocketClient.execute(uri, session -> {
			Flux<String> payloads = receivePayloads(session, symbol.toUpperCase(), connId, "testnet");
			return payloads.then();
		})
				.doOnSubscribe(ignored -> LOGGER.info("EVENT=BOOKTICKER_WS_SUBSCRIBE symbols=1 mode=testnet symbol={} connId={} reason={}", symbol.toUpperCase(), connId, reason))
				.doOnError(error -> LOGGER.warn("EVENT=BOOKTICKER_WS_ERROR mode=testnet symbol={} connId={} reason={}", symbol.toUpperCase(), connId, error.toString()))
				.doFinally(signal -> {
					LOGGER.warn("EVENT=BOOKTICKER_WS_FINALLY mode=testnet symbol={} connId={} signal={}", symbol.toUpperCase(), connId, signal);
					scheduleReconnect("FINALLY_" + signal.name());
				})
				.subscribe(null, error -> LOGGER.warn("EVENT=BOOK_TICKER_STREAM_ERROR connId={} reason={}", connId, error.getMessage()));
		wsRef.set(disposable);
	}

	private Flux<String> receivePayloads(Object session, String symbolHint, long connId, String mode) {
		try {
			var receiveFrames = session.getClass().getMethod("receiveFrames");
			Object frameFlux = receiveFrames.invoke(session);
			if (frameFlux instanceof Flux<?> flux) {
				return flux
						.timeout(Duration.ofSeconds(30))
						.map(frame -> mapFrameToPayload(frame, connId, mode, symbolHint))
						.filter(payload -> payload != null);
			}
		} catch (ReflectiveOperationException ignored) {
		}

		try {
			var receive = session.getClass().getMethod("receive");
			Object msgFlux = receive.invoke(session);
			if (msgFlux instanceof Flux<?> flux) {
				return flux
						.timeout(Duration.ofSeconds(30))
						.map(message -> {
							if (message instanceof WebSocketMessage wsMessage) {
								onInboundMessage(wsMessage, symbolHint, connId, mode);
								return wsMessage.getPayloadAsText();
							}
							return null;
						})
						.filter(payload -> payload != null);
			}
		} catch (ReflectiveOperationException ex) {
			LOGGER.warn("EVENT=BOOKTICKER_WS_ERROR mode={} connId={} reason={}", mode, connId, ex.toString());
		}
		return Flux.empty();
	}

	private String mapFrameToPayload(Object frame, long connId, String mode, String symbolHint) {
		msgCount.incrementAndGet();
		lastMsgMs.set(System.currentTimeMillis());
		if (reconnecting.get()) {
			reconnectAttempt.set(0);
			reconnecting.set(false);
			LOGGER.info("EVENT=BOOKTICKER_RECONNECT_RECOVERED connId={} mode={}", connId, mode);
		}
		if (frame == null) {
			return null;
		}
		String simple = frame.getClass().getSimpleName();
		if ("CloseWebSocketFrame".equals(simple)) {
			logCloseFrameReflective(frame, connId);
			return null;
		}
		try {
			var textMethod = frame.getClass().getMethod("text");
			Object payload = textMethod.invoke(frame);
			if (payload instanceof String text) {
				handleBookTickerMessage(text, symbolHint);
				return text;
			}
		} catch (ReflectiveOperationException ignored) {
		}
		LOGGER.debug("EVENT=BOOKTICKER_WS_FRAME_IGNORED mode={} connId={} frameType={}", mode, connId, simple);
		return null;
	}

	private void logCloseFrameReflective(Object frame, long connId) {
		int code = -1;
		String reason = null;
		try {
			Object statusCode = frame.getClass().getMethod("statusCode").invoke(frame);
			if (statusCode instanceof Number n) {
				code = n.intValue();
			}
			reason = String.valueOf(frame.getClass().getMethod("reasonText").invoke(frame));
		} catch (ReflectiveOperationException ignored) {
		}
		LOGGER.warn("EVENT=BOOKTICKER_WS_CLOSE connId={} code={} reason={}", connId, code, reason);
	}

	private void scheduleReconnect(String reason) {
		if (!running.get()) {
			return;
		}
		if (!reconnecting.compareAndSet(false, true)) {
			return;
		}
		startHealthLogging();
		int attempt = reconnectAttempt.getAndIncrement();
		long base = Math.min(30_000L, 1_000L * (1L << Math.min(attempt, 5)));
		long jitter = ThreadLocalRandom.current().nextLong(0L, 500L);
		long delayMs = base + jitter;
		ScheduledExecutorService exec = healthExec;
		if (exec == null) {
			reconnecting.set(false);
			return;
		}
		try {
			exec.schedule(() -> {
				if (!running.get()) {
					reconnecting.set(false);
					return;
				}
				reconnecting.set(false);
				disposeActiveWs("RECONNECT", "INTERNAL_LOOP");
				LOGGER.warn("EVENT=BOOKTICKER_RECONNECT attempt={} delayMs={} reason={}", attempt, delayMs, reason);
				subscribe("RECONNECT_" + reason);
			}, delayMs, TimeUnit.MILLISECONDS);
		} catch (RejectedExecutionException ex) {
			LOGGER.warn("EVENT=BOOKTICKER_RECONNECT_SCHEDULE_REJECTED reason={}", ex.toString());
			reconnecting.set(false);
		}
	}

	private void disposeActiveWs(String reason, String callerTag) {
		Disposable current = wsRef.getAndSet(null);
		LOGGER.warn("EVENT=BOOKTICKER_DISPOSE_REQUEST reason={} caller={}", reason, callerTag);
		if (current != null && !current.isDisposed()) {
			current.dispose();
		}
	}


	private void onInboundMessage(WebSocketMessage message, String symbolHint, long connId, String mode) {
		msgCount.incrementAndGet();
		lastMsgMs.set(System.currentTimeMillis());
		if (reconnecting.get()) {
			reconnectAttempt.set(0);
			reconnecting.set(false);
			LOGGER.info("EVENT=BOOKTICKER_RECONNECT_RECOVERED connId={} mode={}", connId, mode);
		}
		if ("CLOSE".equalsIgnoreCase(message.getType().name())) {
			logCloseFrame(message, connId);
			return;
		}
		handleBookTickerMessage(message.getPayloadAsText(), symbolHint);
	}

	private void logCloseFrame(WebSocketMessage message, long connId) {
		int code = -1;
		String reason = null;
		try {
			var buffer = message.getPayload().asByteBuffer();
			if (buffer.remaining() >= 2) {
				code = buffer.getShort() & 0xFFFF;
				if (buffer.remaining() > 0) {
					byte[] rest = new byte[buffer.remaining()];
					buffer.get(rest);
					reason = new String(rest, java.nio.charset.StandardCharsets.UTF_8);
				}
			}
		} catch (Exception ignored) {
		}
		LOGGER.warn("EVENT=BOOKTICKER_WS_CLOSE connId={} code={} reason={}", connId, code, reason);
	}

	private void handleBookTickerMessage(String payload, String symbolHint) {
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

	private synchronized void startHealthLogging() {
		ScheduledFuture<?> existing = healthTaskRef.get();
		if (existing != null && !existing.isCancelled() && !existing.isDone()) {
			return;
		}
		ScheduledExecutorService exec = healthExec;
		if (exec == null || (exec instanceof ScheduledThreadPoolExecutor stpe
				&& (stpe.isShutdown() || stpe.isTerminated()))) {
			exec = Executors.newSingleThreadScheduledExecutor(r -> {
				Thread t = new Thread(r, "bookticker-health");
				t.setDaemon(true);
				return t;
			});
			healthExec = exec;
		}
		try {
			ScheduledFuture<?> task = exec.scheduleAtFixedRate(() -> {
				long now = System.currentTimeMillis();
				long last = lastMsgMs.get();
				long age = last <= 0L ? -1L : Math.max(0L, now - last);
				LOGGER.info("EVENT=BOOKTICKER_HEALTH connId={} msgCount={} lastMsgAgeMs={}", currentConnectionId.get(), msgCount.get(), age);
			}, 30, 30, TimeUnit.SECONDS);
			healthTaskRef.set(task);
		} catch (RejectedExecutionException ex) {
			LOGGER.warn("EVENT=BOOKTICKER_HEALTH_SCHEDULE_REJECTED reason={}", ex.toString());
		}
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
