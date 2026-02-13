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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
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

	private final BinanceProperties properties;
	private final ReactorNettyWebSocketClient webSocketClient;
	private final ObjectMapper objectMapper = new ObjectMapper();

	private final Map<String, LiquiditySnapshot> snapshots = new ConcurrentHashMap<>();
	private final AtomicBoolean connected = new AtomicBoolean(false);
	private final AtomicInteger msgCount = new AtomicInteger(0);
	private final AtomicLong lastMsgMs = new AtomicLong(0);
	private final AtomicLong currentConnId = new AtomicLong(0);
	private final AtomicLong connSeq = new AtomicLong(0);

	private final AtomicReference<Disposable> wsRef = new AtomicReference<>();
	private final AtomicReference<ScheduledFuture<?>> healthTaskRef = new AtomicReference<>();
	private ScheduledExecutorService healthExec;

	public record LiquiditySnapshot(
			String symbol,
			double bestBidPrice,
			double bestBidQty,
			double bestAskPrice,
			double bestAskQty,
			long eventTimeMs,
			long timestamp
	) {}

	public BookTickerStreamWatcher(BinanceProperties properties) {
		this.properties = properties;
		this.webSocketClient = new ReactorNettyWebSocketClient();
	}

	@PostConstruct
	public void start() {
		startStream("initial_start");
		scheduleHealthCheck();
	}

	private void startStream(String reason) {
		long connId = connSeq.incrementAndGet();
		currentConnId.set(connId);

		// KRİTİK DEĞİŞİKLİK: !bookTicker (USD-M Futures All Book Tickers)
		// Bu stream tüm sembolleri 1-5 saniye aralıklarla toplu gönderir.
		URI uri = URI.create("wss://fstream.binance.com/ws/!bookTicker");

		LOGGER.info("EVENT=BOOKTICKER_WS_START reason={} connId={} uri={}", reason, connId, uri);

		msgCount.set(0);

		Disposable ws = webSocketClient.execute(uri, session ->
						session.receive()
								.map(msg -> msg.getPayloadAsText()) // Reflection kaldırıldı
								.doOnNext(payload -> {
									lastMsgMs.set(System.currentTimeMillis());
									msgCount.incrementAndGet();
									handleMessage(payload);
								})
								.then()
				)
				.doOnSubscribe(s -> connected.set(true))
				.doFinally(sig -> {
					connected.set(false);
					LOGGER.warn("EVENT=BOOKTICKER_WS_DISCONNECTED sig={} connId={}", sig, connId);
					scheduleReconnect();
				})
				.subscribe();

		wsRef.set(ws);
	}

	private void handleMessage(String payload) {
		try {
			JsonNode node = objectMapper.readTree(payload);

			// !bookTicker verisi genellikle bir Array (liste) olarak gelir
			if (node.isArray()) {
				for (JsonNode item : node) {
					processSingleTicker(item);
				}
			} else {
				// Eğer tekil veri gelirse (Fallback)
				processSingleTicker(node.has("data") ? node.get("data") : node);
			}
		} catch (Exception e) {
			LOGGER.error("EVENT=BOOKTICKER_PARSE_ERROR", e);
		}
	}

	private void processSingleTicker(JsonNode data) {
		String symbol = data.get("s").asText();

		// Sadece ilgilendiğimiz sembolleri belleğe alalım (Opsiyonel: Filtre kaldırılabilir)
		LiquiditySnapshot snap = new LiquiditySnapshot(
				symbol,
				parseDouble(data.get("b")),
				parseDouble(data.get("B")),
				parseDouble(data.get("a")),
				parseDouble(data.get("A")),
				data.get("E") != null ? data.get("E").asLong() : System.currentTimeMillis(),
				System.currentTimeMillis()
		);
		snapshots.put(symbol, snap);
	}

	public LiquiditySnapshot getSnapshot(String symbol) {
		return snapshots.get(symbol.toUpperCase());
	}

	public long lastMsgMs() {
		return lastMsgMs.get();
	}

	private void scheduleReconnect() {
		Executors.newSingleThreadScheduledExecutor().schedule(() -> {
			if (!connected.get()) {
				startStream("reconnect");
			}
		}, 5, TimeUnit.SECONDS);
	}

	private void scheduleHealthCheck() {
		healthExec = Executors.newSingleThreadScheduledExecutor();
		healthExec.scheduleAtFixedRate(() -> {
			long age = System.currentTimeMillis() - lastMsgMs.get();
			// getAndSet(0) kullanarak değeri okuyup anında sıfırlıyoruz
			int currentMsgs = msgCount.getAndSet(0);

//			LOGGER.info("EVENT=BOOKTICKER_STATUS connected={} last_30s_msgCount={} lastMsgAgeMs={}",
//					connected.get(), currentMsgs, (lastMsgMs.get() <= 0 ? -1 : age));

			// 30 saniye boyunca hiç mesaj gelmezse bağlantıyı zorla yenile
			if (age > 30000 && connected.get()) {
				LOGGER.warn("EVENT=BOOKTICKER_TIMEOUT_RESTARTing");
				stopStream();
				startStream("timeout");
			}
		}, 30, 30, TimeUnit.SECONDS);
	}

	@PreDestroy
	public void stopStream() {
		Disposable ws = wsRef.getAndSet(null);
		if (ws != null) ws.dispose();
		if (healthExec != null) healthExec.shutdown();
	}

	private static double parseDouble(JsonNode node) {
		if (node == null || node.isMissingNode() || node.isNull()) return 0.0;
		return node.asDouble();
	}
}