package com.binance.wslogger;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jakarta.annotation.PreDestroy;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

public class SnapshotScheduler {

    private static final Logger log = LoggerFactory.getLogger(SnapshotScheduler.class);

    private final WsSnapshotLoggerProperties properties;
    private final JsonlWriter writer;
    private final Scheduler scheduler;
    private final Map<String, Disposable> tasks = new ConcurrentHashMap<>();

    public SnapshotScheduler(WsSnapshotLoggerProperties properties, JsonlWriter writer) {
        this.properties = properties;
        this.writer = writer;
        this.scheduler = Schedulers.newBoundedElastic(4, 1000, "ws-snapshot-logger");
    }

    public void start(Map<String, PerSymbolStateStore> stores) {
        Duration interval = Duration.ofSeconds(properties.getLogEverySec());
        for (Map.Entry<String, PerSymbolStateStore> entry : stores.entrySet()) {
            String symbol = entry.getKey();
            PerSymbolStateStore store = entry.getValue();
            emitSnapshot(store);
            Disposable task = Flux.interval(interval, interval, scheduler)
                    .onBackpressureDrop()
                    .doOnNext(tick -> emitSnapshot(store))
                    .doOnError(ex -> log.error("EVENT=SNAPSHOT_SCHEDULER_ERROR symbol={} message={}", symbol, ex.getMessage(), ex))
                    .subscribe(null, ex -> log.error("EVENT=SNAPSHOT_SCHEDULER_SUBSCRIBE_ERROR symbol={} message={}", symbol, ex.getMessage(), ex));
            tasks.put(symbol, task);
        }
    }

    @PreDestroy
    public void shutdown() {
        for (Disposable task : tasks.values()) {
            task.dispose();
        }
        tasks.clear();
        scheduler.dispose();
    }

    private void emitSnapshot(PerSymbolStateStore store) {
        try {
            SnapshotRecord snapshot = store.buildSnapshot(System.currentTimeMillis(), properties.getLogEverySec());
            writer.offer(snapshot);
        } catch (Exception ex) {
            log.error("EVENT=SNAPSHOT_BUILD_ERROR symbol={} message={}", store.getSymbol(), ex.getMessage(), ex);
        }
    }
}
