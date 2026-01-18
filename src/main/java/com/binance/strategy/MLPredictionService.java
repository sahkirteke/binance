package com.binance.strategy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.ta4j.core.BarSeries;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

@Service
public class MLPredictionService {
    private static final Logger LOGGER = LoggerFactory.getLogger(MLPredictionService.class);

    private static final String INTERVAL_15M = "15m";
    private static final String INTERVAL_1H = "1h";
    private static final String INTERVAL_4H = "4h";
    private static final String INTERVAL_1D = "1d";

    private final XGBoostTrainingService xgBoostService;
    private final TA4JFeatureService featureService;
    private final MLProperties mlProperties;

    private final Map<String, Map<String, Deque<Candle>>> recentCandles = new ConcurrentHashMap<>();
    private final Map<String, PredictionState> lastPredictions = new ConcurrentHashMap<>();
    private static final int MAX_RECENT_CANDLES = 500;

    public MLPredictionService(XGBoostTrainingService xgBoostService,
                               TA4JFeatureService featureService,
                               MLProperties mlProperties) {
        this.xgBoostService = xgBoostService;
        this.featureService = featureService;
        this.mlProperties = mlProperties;
    }

    public void onNewCandle(String symbol, String interval, Candle candle) {
        if (!mlProperties.enabled()) {
            return;
        }

        Map<String, Deque<Candle>> intervalMap = recentCandles
                .computeIfAbsent(symbol, ignored -> new ConcurrentHashMap<>());
        Deque<Candle> candles = intervalMap
                .computeIfAbsent(interval, ignored -> new ConcurrentLinkedDeque<>());

        candles.addLast(candle);
        while (candles.size() > MAX_RECENT_CANDLES) {
            candles.pollFirst();
        }

        if (!INTERVAL_15M.equals(interval)) {
            return;
        }

        evaluatePreviousPrediction(symbol, candle);
        createPrediction(symbol, intervalMap, candle);
    }

    private void evaluatePreviousPrediction(String symbol, Candle currentCandle) {
        PredictionState previous = lastPredictions.get(symbol);
        if (previous == null) {
            return;
        }

        boolean wentUp = currentCandle.close() > previous.closePrice();
        boolean wentDown = currentCandle.close() < previous.closePrice();
        boolean success = previous.predictedUp() ? wentUp : wentDown;

        if (success) {
            LOGGER.info("TAHMIN_TUTTU {} {}", symbol, previous.predictedUp() ? "LONG" : "SHORT");
        } else {
            LOGGER.info("TAHMIN_TUTMADI {} {}", symbol, previous.predictedUp() ? "LONG" : "SHORT");
        }
    }

    private void createPrediction(String symbol, Map<String, Deque<Candle>> intervalMap, Candle candle) {
        if (!xgBoostService.isSymbolTrained(symbol)) {
            return;
        }

        try {
            List<Candle> baseCandles = new ArrayList<>(intervalMap.getOrDefault(
                    INTERVAL_15M, new ConcurrentLinkedDeque<>()));
            BarSeries series = featureService.createBarSeries(symbol, baseCandles, Duration.ofMinutes(15));

            List<Candle> candles1h = new ArrayList<>(intervalMap
                    .getOrDefault(INTERVAL_1H, new ConcurrentLinkedDeque<>()));
            List<Candle> candles4h = new ArrayList<>(intervalMap
                    .getOrDefault(INTERVAL_4H, new ConcurrentLinkedDeque<>()));
            List<Candle> candles1d = new ArrayList<>(intervalMap
                    .getOrDefault(INTERVAL_1D, new ConcurrentLinkedDeque<>()));

            XGBoostModel.PredictionResult prediction = xgBoostService.predict(
                    symbol,
                    series,
                    new XGBoostTrainingService.TimeframeContext(candles1h, candles4h, candles1d)
            );

            lastPredictions.put(symbol, new PredictionState(prediction.willRise(), candle.close()));

        } catch (Exception ignored) {
            // intentionally silent to keep logs clean
        }
    }

    private record PredictionState(
            boolean predictedUp,
            double closePrice
    ) {}
}
