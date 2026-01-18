package com.binance.strategy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class CsvCandleLoader {
    private static final Logger LOGGER = LoggerFactory.getLogger(CsvCandleLoader.class);

    private final MLProperties mlProperties;

    public CsvCandleLoader(MLProperties mlProperties) {
        this.mlProperties = mlProperties;
    }

    public List<Candle> loadCandles(String symbol, String interval) {
        MLProperties.CsvConfig csvConfig = mlProperties.csv();
        String fileName = csvConfig.filePattern()
                .replace("{symbol}", symbol)
                .replace("{interval}", interval);
        Path path = Path.of(csvConfig.dataDir(), fileName);
        if (!Files.exists(path)) {
            LOGGER.warn("CSV file not found for {} {} at {}", symbol, interval, path.toAbsolutePath());
            return List.of();
        }

        List<Candle> candles = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(path)) {
            String line;
            boolean headerProcessed = false;
            Map<String, Integer> headerIndex = new HashMap<>();
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                String[] parts = splitCsvLine(line, csvConfig.delimiter());
                if (!headerProcessed && csvConfig.hasHeader()) {
                    headerIndex = parseHeader(parts);
                    headerProcessed = true;
                    continue;
                }
                if (!headerProcessed) {
                    headerProcessed = true;
                }
                Candle candle = parseCandle(parts, headerIndex, csvConfig);
                if (candle != null) {
                    candles.add(candle);
                }
            }
        } catch (IOException e) {
            LOGGER.error("Failed to read CSV {}: {}", path, e.getMessage());
        }

        candles.sort(Comparator.comparingLong(Candle::closeTime));
        LOGGER.info("Loaded {} candles for {} {} from {}", candles.size(), symbol, interval, path);
        return candles;
    }

    private String[] splitCsvLine(String line, String delimiter) {
        return line.split(java.util.regex.Pattern.quote(delimiter));
    }

    private Map<String, Integer> parseHeader(String[] header) {
        Map<String, Integer> indexMap = new HashMap<>();
        for (int i = 0; i < header.length; i++) {
            String normalized = normalize(header[i]);
            indexMap.put(normalized, i);
        }
        return indexMap;
    }

    private Candle parseCandle(String[] parts, Map<String, Integer> headerIndex,
                               MLProperties.CsvConfig config) {
        try {
            int openIdx = resolveIndex(headerIndex, config.openColumn(), config.openIndex());
            int highIdx = resolveIndex(headerIndex, config.highColumn(), config.highIndex());
            int lowIdx = resolveIndex(headerIndex, config.lowColumn(), config.lowIndex());
            int closeIdx = resolveIndex(headerIndex, config.closeColumn(), config.closeIndex());
            int volumeIdx = resolveIndex(headerIndex, config.volumeColumn(), config.volumeIndex());
            int closeTimeIdx = resolveIndex(headerIndex, config.closeTimeColumn(), config.closeTimeIndex());

            double open = Double.parseDouble(parts[openIdx]);
            double high = Double.parseDouble(parts[highIdx]);
            double low = Double.parseDouble(parts[lowIdx]);
            double close = Double.parseDouble(parts[closeIdx]);
            double volume = Double.parseDouble(parts[volumeIdx]);
            long closeTime = parseCloseTime(parts[closeTimeIdx], config.closeTimeUnit());

            return new Candle(open, high, low, close, volume, closeTime);
        } catch (Exception e) {
            LOGGER.debug("Skipping invalid CSV row: {}", e.getMessage());
            return null;
        }
    }

    private long parseCloseTime(String value, String unit) {
        if (value == null || value.isBlank()) {
            return 0L;
        }
        long timeValue = Long.parseLong(value.trim());
        String normalizedUnit = unit == null ? "MILLIS" : unit.toUpperCase(Locale.ROOT);
        return switch (normalizedUnit) {
            case "SECONDS" -> Instant.EPOCH.plus(timeValue, ChronoUnit.SECONDS).toEpochMilli();
            case "MICROS" -> Instant.EPOCH.plus(timeValue, ChronoUnit.MICROS).toEpochMilli();
            case "NANOS" -> Instant.EPOCH.plus(timeValue, ChronoUnit.NANOS).toEpochMilli();
            default -> timeValue;
        };
    }

    private int resolveIndex(Map<String, Integer> headerIndex, String column, int fallbackIndex) {
        if (headerIndex == null || headerIndex.isEmpty()) {
            return fallbackIndex;
        }
        String normalized = normalize(column);
        Integer index = headerIndex.get(normalized);
        if (index == null) {
            return fallbackIndex;
        }
        return index;
    }

    private String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.trim()
                .toLowerCase(Locale.ROOT)
                .replace(" ", "")
                .replace("_", "");
    }
}
