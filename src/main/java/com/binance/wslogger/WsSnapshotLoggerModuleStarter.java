package com.binance.wslogger;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;

public class WsSnapshotLoggerModuleStarter implements ApplicationListener<ApplicationReadyEvent> {

    private static final Logger log = LoggerFactory.getLogger(WsSnapshotLoggerModuleStarter.class);

    private final WsSnapshotLoggerProperties properties;
    private final WsMarketDataClient wsMarketDataClient;
    private final SnapshotScheduler snapshotScheduler;
    private final JsonlWriter writer;

    public WsSnapshotLoggerModuleStarter(
            WsSnapshotLoggerProperties properties,
            WsMarketDataClient wsMarketDataClient,
            SnapshotScheduler snapshotScheduler,
            JsonlWriter writer) {
        this.properties = properties;
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
            wsMarketDataClient.start(stores);
        } catch (Exception ex) {
            log.error("EVENT=WS_SNAPSHOT_LOGGER_START_FAILED message={}", ex.getMessage(), ex);
        }
    }
}
