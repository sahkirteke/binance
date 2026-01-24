package com.binance.wslogger;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import jakarta.annotation.PreDestroy;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import reactor.core.Disposable;

public class WsSnapshotLoggerModuleStarter implements ApplicationListener<ApplicationReadyEvent> {

    private static final Logger log = LoggerFactory.getLogger(WsSnapshotLoggerModuleStarter.class);

    private final WsSnapshotLoggerProperties properties;
    private final MarketDataHub marketDataHub;
    private final ObjectProvider<WsMarketDataClient> wsMarketDataClient;
    private final SnapshotScheduler snapshotScheduler;
    private final JsonlWriter writer;
    private Disposable marketDataSubscription;

    public WsSnapshotLoggerModuleStarter(
            WsSnapshotLoggerProperties properties,
            MarketDataHub marketDataHub,
            ObjectProvider<WsMarketDataClient> wsMarketDataClient,
            SnapshotScheduler snapshotScheduler,
            JsonlWriter writer) {
        this.properties = properties;
        this.marketDataHub = marketDataHub;
        this.wsMarketDataClient = wsMarketDataClient;
        this.snapshotScheduler = snapshotScheduler;
        this.writer = writer;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        try {
            if (!properties.isEnabled()) {
                log.info("EVENT=WS_SNAPSHOT_LOGGER_DISABLED");
                return;
            }
            Map<String, PerSymbolStateStore> stores = new HashMap<>();
            for (String symbol : properties.getSymbols()) {
                String normalized = symbol.toUpperCase(Locale.ROOT);
                stores.put(normalized, new PerSymbolStateStore(normalized, properties));
            }
            if (stores.isEmpty()) {
                log.warn("EVENT=WS_SNAPSHOT_LOGGER_NO_SYMBOLS");
                return;
            }
            writer.start();
            Runtime.getRuntime().addShutdownHook(new Thread(writer::shutdown, "ws-snapshot-writer-shutdown"));
            snapshotScheduler.start(stores);
            if ("standalone".equalsIgnoreCase(properties.getMode())) {
                WsMarketDataClient client = wsMarketDataClient.getIfAvailable();
                if (client != null) {
                    client.start(stores);
                } else {
                    log.warn("EVENT=WS_SNAPSHOT_LOGGER_NO_STANDALONE_CLIENT");
                }
            } else {
                marketDataSubscription = marketDataHub.flux()
                        .subscribe(marketEvent -> applyEvent(stores, marketEvent), ex -> log.error(
                                "EVENT=WS_SNAPSHOT_LOGGER_HUB_ERROR message={}",
                                ex.getMessage(),
                                ex));
            }
        } catch (Exception ex) {
            log.error("EVENT=WS_SNAPSHOT_LOGGER_START_FAILED message={}", ex.getMessage(), ex);
        }
    }

    @PreDestroy
    public void shutdown() {
        if (marketDataSubscription != null) {
            marketDataSubscription.dispose();
        }
    }

    private void applyEvent(Map<String, PerSymbolStateStore> stores, MarketEvent event) {
        try {
            PerSymbolStateStore store = stores.get(event.symbol().toUpperCase(Locale.ROOT));
            if (store == null) {
                return;
            }
            if (event instanceof MarkPriceEvent markPriceEvent) {
                store.updateMarkPrice(markPriceEvent.markPrice(), markPriceEvent.eventTimeMs());
            } else if (event instanceof BookTickerEvent bookTickerEvent) {
                store.updateBookTicker(
                        bookTickerEvent.bid(),
                        bookTickerEvent.ask(),
                        bookTickerEvent.bidQty(),
                        bookTickerEvent.askQty(),
                        bookTickerEvent.eventTimeMs());
            } else if (event instanceof AggTradeEvent aggTradeEvent) {
                store.updateAggTrade(
                        aggTradeEvent.quantity(),
                        aggTradeEvent.buyerIsMaker(),
                        aggTradeEvent.eventTimeMs());
            }
        } catch (Exception ex) {
            log.error("EVENT=WS_SNAPSHOT_LOGGER_EVENT_ERROR message={}", ex.getMessage(), ex);
        }
    }
}
