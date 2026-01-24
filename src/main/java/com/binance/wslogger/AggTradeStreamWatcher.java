package com.binance.wslogger;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import reactor.core.Disposable;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;

public class AggTradeStreamWatcher {

    private static final Logger log = LoggerFactory.getLogger(AggTradeStreamWatcher.class);

    private final WsSnapshotLoggerProperties properties;
    private final ObjectMapper objectMapper;
    private final MarketDataHub marketDataHub;
    private final ReactorNettyWebSocketClient webSocketClient;
    private final AtomicReference<Disposable> subscriptionRef = new AtomicReference<>();
    private final Scheduler parseScheduler;

    public AggTradeStreamWatcher(WsSnapshotLoggerProperties properties,
            ObjectMapper objectMapper,
            MarketDataHub marketDataHub) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.marketDataHub = marketDataHub;
        this.webSocketClient = new ReactorNettyWebSocketClient();
        this.parseScheduler = Schedulers.newParallel("ws-agg-trade-parse", 2);
    }

    @PostConstruct
    public void start() {
        List<String> symbols = properties.getSymbols();
        if (symbols == null || symbols.isEmpty()) {
            log.warn("EVENT=AGG_TRADE_STREAM_NO_SYMBOLS");
            return;
        }
        String streamPath = symbols.stream()
                .map(symbol -> symbol.toLowerCase(Locale.ROOT) + "@aggTrade")
                .reduce((left, right) -> left + "/" + right)
                .orElse("");
        if (streamPath.isBlank()) {
            log.warn("EVENT=AGG_TRADE_STREAM_NO_SYMBOLS");
            return;
        }
        URI uri = URI.create(properties.getWsBaseUrl() + "?streams=" + streamPath);
        log.info("EVENT=AGG_TRADE_STREAM_CONNECT uri={}", uri);
        Disposable subscription = webSocketClient.execute(uri, session -> session.receive()
                .map(message -> message.getPayloadAsText())
                .publishOn(parseScheduler)
                .doOnNext(this::handlePayload)
                .then())
                .retryWhen(retrySpec())
                .subscribe(null, error -> log.warn("EVENT=AGG_TRADE_STREAM_ERROR reason={}", error.getMessage()));
        subscriptionRef.set(subscription);
    }

    @PreDestroy
    public void stop() {
        Disposable subscription = subscriptionRef.getAndSet(null);
        if (subscription != null) {
            subscription.dispose();
        }
        parseScheduler.dispose();
    }

    private void handlePayload(String payload) {
        try {
            JsonNode node = objectMapper.readTree(payload);
            JsonNode dataNode = node.hasNonNull("data") ? node.get("data") : node;
            if (dataNode == null || dataNode.isNull()) {
                return;
            }
            String symbol = dataNode.path("s").asText();
            if (symbol == null || symbol.isBlank()) {
                return;
            }
            double quantity = dataNode.path("q").asDouble(0.0);
            boolean buyerIsMaker = dataNode.path("m").asBoolean(false);
            long eventTime = dataNode.path("E").asLong(System.currentTimeMillis());
            marketDataHub.publish(new AggTradeEvent(symbol, quantity, buyerIsMaker, eventTime));
        } catch (Exception ex) {
            log.warn("EVENT=AGG_TRADE_PARSE_FAIL reason={}", ex.getMessage());
        }
    }

    private Retry retrySpec() {
        return Retry.backoff(Long.MAX_VALUE, properties.getReconnectBackoffMin())
                .maxBackoff(properties.getReconnectBackoffMax())
                .jitter(0.3)
                .doBeforeRetry(signal -> log.warn("EVENT=AGG_TRADE_STREAM_RECONNECT attempt={} reason={}",
                        signal.totalRetries(),
                        signal.failure().getMessage()));
    }
}
