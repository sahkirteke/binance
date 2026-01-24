package com.binance.wslogger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JsonlWriter {

    private static final Logger log = LoggerFactory.getLogger(JsonlWriter.class);
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.BASIC_ISO_DATE;

    private final WsSnapshotLoggerProperties properties;
    private final ObjectWriter objectWriter;
    private final BlockingQueue<SnapshotRecord> queue;
    private final ZoneId zoneId;
    private final AtomicLong droppedCounter = new AtomicLong();
    private final AtomicLong lastWarnEpochMs = new AtomicLong();
    private final Map<String, WriterState> writerStateBySymbol = new HashMap<>();

    private volatile boolean running;
    private Thread writerThread;

    public JsonlWriter(WsSnapshotLoggerProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectWriter = objectMapper.writer();
        this.queue = new ArrayBlockingQueue<>(properties.getWriterQueueCapacity());
        this.zoneId = ZoneId.of("Europe/Istanbul");
    }

    public void start() {
        if (running) {
            return;
        }
        running = true;
        writerThread = new Thread(this::runLoop, "ws-snapshot-writer");
        writerThread.setDaemon(true);
        writerThread.start();
    }

    public void offer(SnapshotRecord snapshot) {
        if (!queue.offer(snapshot)) {
            long dropped = droppedCounter.incrementAndGet();
            long now = System.currentTimeMillis();
            long lastWarn = lastWarnEpochMs.get();
            if (now - lastWarn > 30000 && lastWarnEpochMs.compareAndSet(lastWarn, now)) {
                log.warn("EVENT=SNAPSHOT_DROPPED droppedCount={}", dropped);
            }
        }
    }

    public void shutdown() {
        running = false;
        if (writerThread != null) {
            writerThread.interrupt();
            try {
                writerThread.join(2000);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
        closeAll();
    }

    private void runLoop() {
        long lastFlush = System.currentTimeMillis();
        try {
            while (running || !queue.isEmpty()) {
                try {
                    SnapshotRecord snapshot = queue.poll(500, TimeUnit.MILLISECONDS);
                    if (snapshot != null) {
                        writeSnapshot(snapshot);
                    }
                    long now = System.currentTimeMillis();
                    if (now - lastFlush >= properties.getWriterFlushInterval().toMillis()) {
                        flushAll();
                        lastFlush = now;
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                } catch (Exception ex) {
                    log.error("EVENT=SNAPSHOT_WRITE_ERROR message={}", ex.getMessage(), ex);
                }
            }
            flushAll();
        } finally {
            closeAll();
        }
    }

    private void writeSnapshot(SnapshotRecord snapshot) throws IOException {
        WriterState state = writerStateBySymbol.computeIfAbsent(snapshot.getSymbol(), key -> new WriterState());
        LocalDate date = Instant.ofEpochMilli(snapshot.getT()).atZone(zoneId).toLocalDate();
        if (state.currentDate == null || !state.currentDate.equals(date)) {
            rotateWriter(snapshot.getSymbol(), date, state);
        }
        String line = objectWriter.writeValueAsString(snapshot);
        state.writer.write(line);
        state.writer.write("\n");
    }

    private void rotateWriter(String symbol, LocalDate date, WriterState state) throws IOException {
        closeWriter(state);
        Path baseDir = properties.getBaseDir();
        Files.createDirectories(baseDir);
        String fileName = symbol + "-" + DATE_FORMAT.format(date) + ".jsonl";
        Path filePath = baseDir.resolve(fileName);
        state.writer = Files.newBufferedWriter(filePath);
        state.currentDate = date;
    }

    private void flushAll() {
        for (WriterState state : writerStateBySymbol.values()) {
            try {
                if (state.writer != null) {
                    state.writer.flush();
                }
            } catch (IOException ex) {
                log.error("EVENT=SNAPSHOT_FLUSH_ERROR message={}", ex.getMessage(), ex);
            }
        }
    }

    private void closeAll() {
        for (WriterState state : writerStateBySymbol.values()) {
            closeWriter(state);
        }
        writerStateBySymbol.clear();
    }

    private void closeWriter(WriterState state) {
        if (state.writer != null) {
            try {
                state.writer.flush();
                state.writer.close();
            } catch (IOException ex) {
                log.error("EVENT=SNAPSHOT_CLOSE_ERROR message={}", ex.getMessage(), ex);
            } finally {
                state.writer = null;
                state.currentDate = null;
            }
        }
    }

    private static final class WriterState {
        private BufferedWriter writer;
        private LocalDate currentDate;
    }
}
