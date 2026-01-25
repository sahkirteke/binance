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

public class CombinedMarketStreamWatcher {

    private static final Logger log = LoggerFactory.getLogger(CombinedMarketStreamWatcher.class);
    private static final String EVENT_BOOK_TICKER = "bookTicker";
    private static final String EVENT_AGG_TRADE = "aggTrade";

    private final WsSnapshotLoggerProperties properties;
    private final ObjectMapper objectMapper;
    private final MarketDataHub marketDataHub;
    private final ReactorNettyWebSocketClient webSocketClient;
    private final AtomicReference<Disposable> subscriptionRef = new AtomicReference<>();
    private final Scheduler parseScheduler;

    public CombinedMarketStreamWatcher(WsSnapshotLoggerProperties properties,
            ObjectMapper objectMapper,
            MarketDataHub marketDataHub) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.marketDataHub = marketDataHub;
        this.webSocketClient = new ReactorNettyWebSocketClient();
        this.parseScheduler = Schedulers.newParallel("ws-market-parse", 2);
    }

    @PostConstruct
    public void start() {
        List<String> symbols = properties.getSymbols();
        if (symbols == null || symbols.isEmpty()) {
            log.warn("EVENT=MARKET_STREAM_NO_SYMBOLS");
            return;
        }
        String streamPath = symbols.stream()
                .flatMap(symbol -> java.util.stream.Stream.of(
                        symbol.toLowerCase(Locale.ROOT) + "@bookTicker",
                        symbol.toLowerCase(Locale.ROOT) + "@aggTrade"))
                .reduce((left, right) -> left + "/" + right)
                .orElse("");
        if (streamPath.isBlank()) {
            log.warn("EVENT=MARKET_STREAM_NO_SYMBOLS");
            return;
        }
        URI uri = URI.create(properties.getWsBaseUrl() + "?streams=" + streamPath);
        log.info("EVENT=MARKET_STREAM_CONNECT uri={}", uri);
        Disposable subscription = webSocketClient.execute(uri, session -> session.receive()
                .map(message -> message.getPayloadAsText())
                .publishOn(parseScheduler)
                .doOnNext(this::handlePayload)
                .then())
                .retryWhen(retrySpec())
                .subscribe(null, error -> log.warn("EVENT=MARKET_STREAM_ERROR reason={}", error.getMessage()));
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
            JsonNode root = objectMapper.readTree(payload);
            JsonNode dataNode = root.hasNonNull("data") ? root.get("data") : root;
            if (dataNode == null || dataNode.isNull()) {
                return;
            }
            String eventType = dataNode.path("e").asText();
            if (EVENT_BOOK_TICKER.equals(eventType)) {
                handleBookTicker(dataNode);
            } else if (EVENT_AGG_TRADE.equals(eventType)) {
                handleAggTrade(dataNode);
            }
        } catch (Exception ex) {
            log.warn("EVENT=MARKET_STREAM_PARSE_FAIL reason={}", ex.getMessage());
        }
    }

    private void handleBookTicker(JsonNode node) {
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

    private void handleAggTrade(JsonNode node) {
        String symbol = node.path("s").asText();
        if (symbol == null || symbol.isBlank()) {
            return;
        }
        double quantity = node.path("q").asDouble(0.0);
        boolean buyerIsMaker = node.path("m").asBoolean(false);
        long eventTime = node.path("E").asLong(System.currentTimeMillis());
        marketDataHub.publish(new AggTradeEvent(symbol, quantity, buyerIsMaker, eventTime));
    }

    private Retry retrySpec() {
        return Retry.backoff(Long.MAX_VALUE, properties.getReconnectBackoffMin())
                .maxBackoff(properties.getReconnectBackoffMax())
                .jitter(0.3)
                .doBeforeRetry(signal -> log.warn("EVENT=MARKET_STREAM_RECONNECT attempt={} reason={}",
                        signal.totalRetries(),
                        signal.failure().getMessage()));
    }
}
