package com.binance.wslogger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import reactor.netty.http.client.HttpClient;
import jakarta.annotation.PreDestroy;

public class WsMarketDataClient {

    private static final Logger log = LoggerFactory.getLogger(WsMarketDataClient.class);
    private static final String EVENT_MARK_PRICE = "markPriceUpdate";
    private static final String EVENT_BOOK_TICKER = "bookTicker";
    private static final String EVENT_AGG_TRADE = "aggTrade";
    private static final long RECONNECT_WARN_INTERVAL_MS = 30000L;

    private final WsSnapshotLoggerProperties properties;
    private final ObjectMapper objectMapper;
    private final ReactorNettyWebSocketClient webSocketClient;
    private final Scheduler reconnectScheduler;
    private final Scheduler messageScheduler;
    private final Map<String, Disposable> connections = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> reconnectAttempts = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> shortDisconnects = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> lastReconnectWarn = new ConcurrentHashMap<>();
    private final AtomicLong globalNextReconnectAtMs = new AtomicLong();

    public WsMarketDataClient(WsSnapshotLoggerProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.webSocketClient = buildClient();
        this.reconnectScheduler = Schedulers.newBoundedElastic(4, 1000, "ws-snapshot-reconnect");
        this.messageScheduler = Schedulers.newParallel("ws-snapshot-msg", 4);
    }

    public void start(Map<String, PerSymbolStateStore> stores) {
        for (Map.Entry<String, PerSymbolStateStore> entry : stores.entrySet()) {
            String symbol = entry.getKey();
            PerSymbolStateStore store = entry.getValue();
            Disposable disposable = connectLoop(symbol, store).subscribe();
            connections.put(symbol, disposable);
        }
    }

    @PreDestroy
    public void shutdown() {
        for (Disposable disposable : connections.values()) {
            disposable.dispose();
        }
        connections.clear();
        reconnectScheduler.dispose();
        messageScheduler.dispose();
    }

    private ReactorNettyWebSocketClient buildClient() {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, Math.toIntExact(properties.getConnectTimeout().toMillis()))
                .responseTimeout(properties.getResponseTimeout())
                .doOnConnected(conn -> conn.addHandlerLast(new ReadTimeoutHandler(properties.getReadTimeout().toSeconds())));
        return new ReactorNettyWebSocketClient(httpClient);
    }

    private Mono<Void> connectLoop(String symbol, PerSymbolStateStore store) {
        return Mono.defer(() -> connectOnce(symbol, store))
                .onErrorResume(ex -> {
                    Duration delay = nextDelay(symbol, ex);
                    if (shouldWarnReconnect(symbol)) {
                        log.warn("EVENT=WS_RECONNECT_SCHEDULED symbol={} delayMs={} message={}", symbol, delay.toMillis(), ex.getMessage());
                    }
                    return Mono.delay(delay, reconnectScheduler).then(connectLoop(symbol, store));
                });
    }

    private Mono<Void> connectOnce(String symbol, PerSymbolStateStore store) {
        URI uri = URI.create(buildUrl(symbol));
        AtomicLong lastPong = new AtomicLong(System.currentTimeMillis());
        AtomicLong lastPingSeen = new AtomicLong(System.currentTimeMillis());
        AtomicLong lastTraffic = new AtomicLong(System.currentTimeMillis());
        long connectStart = System.currentTimeMillis();
        log.info("EVENT=WS_CONNECT symbol={} uri={}", symbol, uri);
        return webSocketClient.execute(uri, session -> {
            log.info("EVENT=WS_CONNECTED symbol={}", symbol);
            Sinks.Many<WebSocketMessage> outbound = Sinks.many().unicast().onBackpressureBuffer();
            Mono<Void> receive = session.receive()
                    .publishOn(messageScheduler)
                    .doOnNext(message -> handleMessage(symbol, store, session, outbound, message, lastPong, lastPingSeen, lastTraffic))
                    .doOnError(ex -> log.error("EVENT=WS_DISCONNECTED symbol={} message={}", symbol, ex.getMessage(), ex))
                    .then();

            Flux<WebSocketMessage> pingFlux = properties.isClientPingEnabled()
                    ? Flux.interval(properties.getPingInterval())
                    .takeUntilOther(receive)
                    .map(tick -> {
                        log.debug("EVENT=WS_PING_SENT symbol={}", symbol);
                        return session.pingMessage(dataBufferFactory -> dataBufferFactory.wrap(new byte[] { 1 }));
                    })
                    .doOnNext(message -> outbound.tryEmitNext(message))
                    : Flux.empty();

            Mono<Void> sendOutbound = session.send(outbound.asFlux()).then();

            Mono<Void> monitorPong = Flux.interval(properties.getPongTimeout())
                    .takeUntilOther(receive)
                    .doOnNext(tick -> {
                        long now = System.currentTimeMillis();
                        long lastTrafficDelta = now - lastTraffic.get();
                        long lastPongDelta = now - lastPong.get();
                        boolean trafficStale = lastTrafficDelta > properties.getReadTimeout().toMillis();
                        boolean pongStale = properties.isClientPingEnabled()
                                && lastPongDelta > properties.getPongTimeout().toMillis();
                        if (trafficStale || pongStale) {
                            log.warn("EVENT=WS_PONG_TIMEOUT symbol={}", symbol);
                            session.close().subscribe();
                        }
                    })
                    .then();

            return Mono.when(sendOutbound, pingFlux.then(), monitorPong, receive)
                    .doFinally(signalType -> log.info("EVENT=WS_DISCONNECTED symbol={} signal={}", symbol, signalType));
        }).then(Mono.error(new IllegalStateException("WebSocket session ended")))
                .doOnError(ex -> updateDisconnectStats(symbol, connectStart, ex));
    }

    private void handleMessage(
            String symbol,
            PerSymbolStateStore store,
            WebSocketSession session,
            Sinks.Many<WebSocketMessage> outbound,
            WebSocketMessage message,
            AtomicLong lastPong,
            AtomicLong lastPingSeen,
            AtomicLong lastTraffic) {
        if (message.getType() == WebSocketMessage.Type.PING) {
            byte[] payload = new byte[message.getPayload().readableByteCount()];
            message.getPayload().read(payload);
            outbound.tryEmitNext(session.pongMessage(factory -> factory.wrap(payload)));
            lastPingSeen.set(System.currentTimeMillis());
            log.debug("EVENT=WS_PONG_SENT symbol={}", symbol);
            return;
        }
        if (message.getType() == WebSocketMessage.Type.PONG) {
            lastPong.set(System.currentTimeMillis());
            log.debug("EVENT=WS_PONG_RCVD symbol={}", symbol);
            return;
        }
        if (message.getType() != WebSocketMessage.Type.TEXT) {
            return;
        }
        try {
            lastTraffic.set(System.currentTimeMillis());
            JsonNode root = objectMapper.readTree(message.getPayloadAsText());
            JsonNode dataNode = root.has("data") ? root.get("data") : root;
            String eventType = dataNode.path("e").asText();
            long eventTime = dataNode.path("E").asLong(System.currentTimeMillis());
            if (EVENT_MARK_PRICE.equals(eventType)) {
                double price = dataNode.path("p").asDouble();
                store.updateMarkPrice(price, eventTime);
            } else if (EVENT_BOOK_TICKER.equals(eventType)) {
                double bid = dataNode.path("b").asDouble();
                double ask = dataNode.path("a").asDouble();
                double bidQty = dataNode.path("B").asDouble();
                double askQty = dataNode.path("A").asDouble();
                store.updateBookTicker(bid, ask, bidQty, askQty, eventTime);
            } else if (EVENT_AGG_TRADE.equals(eventType)) {
                double qty = dataNode.path("q").asDouble();
                boolean buyerIsMaker = dataNode.path("m").asBoolean();
                store.updateAggTrade(qty, buyerIsMaker, eventTime);
            }
        } catch (Exception ex) {
            log.error("EVENT=WS_PARSE_ERROR symbol={} message={}", symbol, ex.getMessage(), ex);
        }
    }

    private String buildUrl(String symbol) {
        String lowerSymbol = symbol.toLowerCase(Locale.ROOT);
        String markPrice = lowerSymbol + "@markPrice@1s";
        // Using per-symbol bookTicker reduces traffic versus !bookTicker for the full market.
        // This keeps the logger isolated and lighter for ~15 symbols.
        String bookTicker = lowerSymbol + "@bookTicker";
        String aggTrade = lowerSymbol + "@aggTrade";
        return properties.getWsBaseUrl() + "?streams=" + markPrice + "/" + bookTicker + "/" + aggTrade;
    }

    private Duration nextDelay(String symbol, Throwable ex) {
        AtomicLong shortCount = shortDisconnects.computeIfAbsent(symbol, key -> new AtomicLong());
        boolean banSuspected = isBanSuspected(ex) || shortCount.get() >= properties.getBanDisconnectThreshold();
        if (banSuspected) {
            log.warn("EVENT=WS_IP_BAN_SUSPECTED cooldownMinutes={}", properties.getBanCooldown().toMinutes());
            return properties.getBanCooldown();
        }
        AtomicInteger attempts = reconnectAttempts.computeIfAbsent(symbol, key -> new AtomicInteger());
        int attempt = attempts.incrementAndGet();
        if (attempt > properties.getMaxReconnectAttempts()) {
            attempts.set(0);
            return properties.getBanCooldown();
        }
        double jitter = 0.7 + ThreadLocalRandom.current().nextDouble() * 0.6;
        long base = Math.min(
                properties.getReconnectBackoffMin().toMillis() * (1L << Math.min(attempt, 10)),
                properties.getReconnectBackoffMax().toMillis());
        long delayMs = (long) (base * jitter);
        long now = System.currentTimeMillis();
        long globalGate = Math.max(0L, globalNextReconnectAtMs.get() - now);
        delayMs = Math.max(delayMs, globalGate);
        long spacing = properties.getReconnectGlobalSpacing().toMillis();
        long scheduledAt = now + delayMs;
        globalNextReconnectAtMs.updateAndGet(prev -> Math.max(prev, scheduledAt + spacing));
        return Duration.ofMillis(delayMs);
    }

    private void updateDisconnectStats(String symbol, long connectStart, Throwable ex) {
        long durationMs = System.currentTimeMillis() - connectStart;
        AtomicLong shortCount = shortDisconnects.computeIfAbsent(symbol, key -> new AtomicLong());
        if (durationMs < properties.getStableConnectionSeconds() * 1000L) {
            shortCount.incrementAndGet();
        } else {
            shortCount.set(0);
            reconnectAttempts.computeIfAbsent(symbol, key -> new AtomicInteger()).set(0);
        }
        if (isRateLimit(ex)) {
            log.warn("EVENT=WS_RATE_LIMIT message={}", ex.getMessage());
        }
    }

    private boolean isRateLimit(Throwable ex) {
        String message = ex.getMessage();
        return message != null && (message.contains("429") || message.contains("418"));
    }

    private boolean isBanSuspected(Throwable ex) {
        return isRateLimit(ex);
    }

    private boolean shouldWarnReconnect(String symbol) {
        long now = System.currentTimeMillis();
        AtomicLong lastWarn = lastReconnectWarn.computeIfAbsent(symbol, key -> new AtomicLong(0L));
        long previous = lastWarn.get();
        if (now - previous >= RECONNECT_WARN_INTERVAL_MS && lastWarn.compareAndSet(previous, now)) {
            return true;
        }
        return false;
    }
}
