package com.binance.wslogger;

import java.net.URI;
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

public class BookTickerStreamWatcher {

    private static final Logger log = LoggerFactory.getLogger(BookTickerStreamWatcher.class);

    private final WsSnapshotLoggerProperties properties;
    private final ObjectMapper objectMapper;
    private final MarketDataHub marketDataHub;
    private final ReactorNettyWebSocketClient webSocketClient;
    private final AtomicReference<Disposable> subscriptionRef = new AtomicReference<>();
    private final Scheduler parseScheduler;

    public BookTickerStreamWatcher(WsSnapshotLoggerProperties properties,
            ObjectMapper objectMapper,
            MarketDataHub marketDataHub) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.marketDataHub = marketDataHub;
        this.webSocketClient = new ReactorNettyWebSocketClient();
        this.parseScheduler = Schedulers.newParallel("ws-book-ticker-parse", 2);
    }

    @PostConstruct
    public void start() {
        URI uri = URI.create(properties.getWsBaseUrl() + "?streams=!bookTicker");
        log.info("EVENT=BOOK_TICKER_STREAM_CONNECT uri={}", uri);
        Disposable subscription = webSocketClient.execute(uri, session -> session.receive()
                .map(message -> message.getPayloadAsText())
                .publishOn(parseScheduler)
                .doOnNext(this::handlePayload)
                .then())
                .retryWhen(retrySpec())
                .subscribe(null, error -> log.warn("EVENT=BOOK_TICKER_STREAM_ERROR reason={}", error.getMessage()));
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
            if (dataNode.isArray()) {
                for (JsonNode entry : dataNode) {
                    handleBookTickerNode(entry);
                }
            } else if (dataNode != null && !dataNode.isNull()) {
                handleBookTickerNode(dataNode);
            }
        } catch (Exception ex) {
            log.warn("EVENT=BOOK_TICKER_PARSE_FAIL reason={}", ex.getMessage());
        }
    }

    private void handleBookTickerNode(JsonNode node) {
        String symbol = node.path("s").asText();
        if (symbol == null || symbol.isBlank()) {
            return;
        }
        double bid = node.path("b").asDouble(0.0);
        double ask = node.path("a").asDouble(0.0);
        double bidQty = node.path("B").asDouble(0.0);
        double askQty = node.path("A").asDouble(0.0);
        long eventTime = node.path("E").asLong(System.currentTimeMillis());
        marketDataHub.publish(new BookTickerEvent(symbol, bid, ask, bidQty, askQty, eventTime));
    }

    private Retry retrySpec() {
        return Retry.backoff(Long.MAX_VALUE, properties.getReconnectBackoffMin())
                .maxBackoff(properties.getReconnectBackoffMax())
                .jitter(0.3)
                .doBeforeRetry(signal -> log.warn("EVENT=BOOK_TICKER_STREAM_RECONNECT attempt={} reason={}",
                        signal.totalRetries(),
                        signal.failure().getMessage()));
    }
}
