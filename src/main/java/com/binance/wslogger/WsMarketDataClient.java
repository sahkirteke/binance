package com.binance.wslogger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
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
    private final Set<String> markPriceLogged = ConcurrentHashMap.newKeySet();
    private final Set<String> bookTickerLogged = ConcurrentHashMap.newKeySet();
    private final Set<String> aggTradeLogged = ConcurrentHashMap.newKeySet();

    public WsMarketDataClient(WsSnapshotLoggerProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.webSocketClient = buildClient();
        this.reconnectScheduler = Schedulers.newBoundedElastic(4, 1000, "ws-snapshot-reconnect");
        this.messageScheduler = Schedulers.newParallel("ws-snapshot-msg", 4);
    }

    public void start(Map<String, PerSymbolStateStore> stores) {
        if (stores.isEmpty()) {
            return;
        }
        Disposable disposable = connectLoop(stores)
                .subscribe(null, error -> log.warn("EVENT=WS_CLIENT_SUBSCRIBE_FAIL reason={}", error.getMessage()));
        connections.put("combined", disposable);
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
                .doOnConnected(conn -> conn.addHandlerLast(new ReadTimeoutHandler(
                        Math.toIntExact(properties.getReadTimeout().getSeconds()))));
        return new ReactorNettyWebSocketClient(httpClient);
    }

    private Mono<Void> connectLoop(Map<String, PerSymbolStateStore> stores) {
        return Mono.defer(() -> connectOnce(stores))
                .onErrorResume(ex -> {
                    Duration delay = nextDelay("combined", ex);
                    if (shouldWarnReconnect("combined")) {
                        log.warn("EVENT=WS_RECONNECT_SCHEDULED symbol=combined delayMs={} message={}", delay.toMillis(), ex.getMessage());
                    }
                    return Mono.delay(delay, reconnectScheduler).then(connectLoop(stores));
                });
    }

    private Mono<Void> connectOnce(Map<String, PerSymbolStateStore> stores) {
        URI uri = URI.create(buildUrl(stores));
        AtomicLong lastPong = new AtomicLong(System.currentTimeMillis());
        AtomicLong lastPingSeen = new AtomicLong(System.currentTimeMillis());
        AtomicLong lastTraffic = new AtomicLong(System.currentTimeMillis());
        AtomicBoolean closeRequested = new AtomicBoolean(false);
        long connectStart = System.currentTimeMillis();
        log.info("EVENT=WS_CONNECT symbol=combined uri={}", uri);
        return webSocketClient.execute(uri, session -> {
            log.info("EVENT=WS_CONNECTED symbol=combined");
            Sinks.Many<WebSocketMessage> outbound = Sinks.many().unicast().onBackpressureBuffer();
            Flux<WebSocketMessage> inbound = session.receive().publish().refCount(1);
            Mono<Void> receiveDone = inbound
                    .doOnNext(message -> handleControlMessage(session, outbound, message, lastPong, lastPingSeen))
                    .filter(message -> message.getType() == WebSocketMessage.Type.TEXT)
                    .map(WebSocketMessage::getPayloadAsText)
                    .publishOn(messageScheduler)
                    .doOnNext(payload -> handleTextPayload(stores, payload, lastTraffic))
                    .doOnError(ex -> log.error("EVENT=WS_DISCONNECTED symbol=combined message={}", ex.getMessage(), ex))
                    .then()
                    .cache();

            Flux<WebSocketMessage> pingFlux = properties.isClientPingEnabled()
                    ? Flux.interval(properties.getPingInterval())
                    .takeUntilOther(receiveDone)
                    .map(tick -> {
                        log.debug("EVENT=WS_PING_SENT symbol=combined");
                        return session.pingMessage(dataBufferFactory -> dataBufferFactory.wrap(new byte[] { 1 }));
                    })
                    .doOnNext(message -> outbound.tryEmitNext(message))
                    : Flux.empty();

            Mono<Void> sendOutbound = session.send(outbound.asFlux()).then();

            Mono<Void> monitorPong = Flux.interval(properties.getPongTimeout())
                    .takeUntilOther(receiveDone)
                    .concatMap(tick -> {
                        long now = System.currentTimeMillis();
                        long lastTrafficDelta = now - lastTraffic.get();
                        long lastPongDelta = now - lastPong.get();
                        boolean trafficStale = lastTrafficDelta > properties.getReadTimeout().toMillis();
                        boolean pongStale = properties.isClientPingEnabled()
                                && lastPongDelta > properties.getPongTimeout().toMillis();
                        if (trafficStale || pongStale) {
                            log.warn("EVENT=WS_PONG_TIMEOUT symbol=combined");
                            if (closeRequested.compareAndSet(false, true)) {
                                return session.close();
                            }
                        }
                        return Mono.<Void>empty();
                    })
                    .then();

            return Mono.when(sendOutbound, pingFlux.then(), monitorPong, receiveDone)
                    .doFinally(signalType -> log.info("EVENT=WS_DISCONNECTED symbol=combined signal={}", signalType));
        }).then(Mono.<Void>error(new IllegalStateException("WebSocket session ended")))
                .doOnError(ex -> updateDisconnectStats("combined", connectStart, ex));
    }

    private void handleControlMessage(
            WebSocketSession session,
            Sinks.Many<WebSocketMessage> outbound,
            WebSocketMessage message,
            AtomicLong lastPong,
            AtomicLong lastPingSeen) {
        if (message.getType() == WebSocketMessage.Type.PING) {
            byte[] payload = new byte[message.getPayload().readableByteCount()];
            message.getPayload().read(payload);
            outbound.tryEmitNext(session.pongMessage(factory -> factory.wrap(payload)));
            lastPingSeen.set(System.currentTimeMillis());
            log.debug("EVENT=WS_PONG_SENT symbol=combined");
            return;
        }
        if (message.getType() == WebSocketMessage.Type.PONG) {
            lastPong.set(System.currentTimeMillis());
            log.debug("EVENT=WS_PONG_RCVD symbol=combined");
            return;
        }
    }

    private void handleTextPayload(
            Map<String, PerSymbolStateStore> stores,
            String payload,
            AtomicLong lastTraffic) {
        try {
            lastTraffic.set(System.currentTimeMillis());
            JsonNode root = objectMapper.readTree(payload);
            JsonNode dataNode = root.has("data") ? root.get("data") : root;
            String symbol = dataNode.path("s").asText();
            if (symbol == null || symbol.isBlank()) {
                return;
            }
            PerSymbolStateStore store = stores.get(symbol.toUpperCase(Locale.ROOT));
            if (store == null) {
                return;
            }
            String eventType = dataNode.path("e").asText();
            long eventTime = dataNode.path("E").asLong(System.currentTimeMillis());
            if (EVENT_MARK_PRICE.equals(eventType)) {
                double price = dataNode.path("p").asDouble();
                store.updateMarkPrice(price, eventTime);
                logFirstData(markPriceLogged, symbol, "markPrice");
            } else if (EVENT_BOOK_TICKER.equals(eventType)) {
                double bid = dataNode.path("b").asDouble();
                double ask = dataNode.path("a").asDouble();
                double bidQty = dataNode.path("B").asDouble();
                double askQty = dataNode.path("A").asDouble();
                store.updateBookTicker(bid, ask, bidQty, askQty, eventTime);
                logFirstData(bookTickerLogged, symbol, "bookTicker");
            } else if (EVENT_AGG_TRADE.equals(eventType)) {
                double qty = dataNode.path("q").asDouble();
                boolean buyerIsMaker = dataNode.path("m").asBoolean();
                store.updateAggTrade(qty, buyerIsMaker, eventTime);
                logFirstData(aggTradeLogged, symbol, "aggTrade");
            }
        } catch (Exception ex) {
            log.error("EVENT=WS_PARSE_ERROR symbol=combined message={}", ex.getMessage(), ex);
        }
    }

    private String buildUrl(Map<String, PerSymbolStateStore> stores) {
        String streams = stores.keySet().stream()
                .map(symbol -> symbol.toLowerCase(Locale.ROOT) + "@bookTicker/"
                        + symbol.toLowerCase(Locale.ROOT) + "@aggTrade")
                .reduce((left, right) -> left + "/" + right)
                .orElse("");
        return properties.getWsBaseUrl() + "?streams=" + streams;
    }

    private void logFirstData(Set<String> tracker, String symbol, String type) {
        if (tracker.add(symbol)) {
            log.info("EVENT=WS_DATA_OK symbol={} type={}", symbol, type);
        }
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
