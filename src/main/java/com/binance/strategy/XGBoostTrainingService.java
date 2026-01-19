package com.binance.strategy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.ta4j.core.BarSeries;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class XGBoostTrainingService {
    private static final Logger LOGGER = LoggerFactory.getLogger(XGBoostTrainingService.class);

    private static final String INTERVAL_15M = "15m";
    private static final String INTERVAL_1H = "1h";
    private static final String INTERVAL_4H = "4h";
    private static final String INTERVAL_1D = "1d";

    private final CsvCandleLoader candleLoader;
    private final TA4JFeatureService featureService;
    private final XGBoostModel xgBoostModel;
    private final MLProperties mlProperties;

    // Eğitim sonuçları cache'i
    private final Map<String, XGBoostModel.TrainingResult> trainingResults = new ConcurrentHashMap<>();

    public XGBoostTrainingService(CsvCandleLoader candleLoader,
                                  TA4JFeatureService featureService,
                                  XGBoostModel xgBoostModel,
                                  MLProperties mlProperties) {
        this.candleLoader = candleLoader;
        this.featureService = featureService;
        this.xgBoostModel = xgBoostModel;
        this.mlProperties = mlProperties;
    }

    // Sembol için model eğit
    public Mono<XGBoostModel.TrainingResult> trainModelForSymbol(String symbol) {
        return Mono.fromCallable(() -> {
            List<Candle> candles15m = candleLoader.loadCandles(symbol, INTERVAL_15M);
            List<Candle> candles1h = candleLoader.loadCandles(symbol, INTERVAL_1H);
            List<Candle> candles4h = candleLoader.loadCandles(symbol, INTERVAL_4H);
            List<Candle> candles1d = candleLoader.loadCandles(symbol, INTERVAL_1D);

            if (candles15m.size() < 1500) {
                throw new IllegalStateException("Insufficient 15m data for " + symbol);
            }

            long startTime = System.currentTimeMillis();

            BarSeries series = featureService.createBarSeries(symbol, candles15m, Duration.ofMinutes(15));

            List<Map<String, Double>> allFeatures = featureService.extractAllFeatures(series);
            List<Boolean> targets = featureService.createTargets(
                    series,
                    mlProperties.lookAheadBars(),
                    0.001 // %0.1 threshold
            );

            TimeframeContext context = new TimeframeContext(candles1h, candles4h, candles1d);
            NavigableMap<Long, Candle> map1h = buildTimeIndex(context.candles1h());
            NavigableMap<Long, Candle> map4h = buildTimeIndex(context.candles4h());
            NavigableMap<Long, Candle> map1d = buildTimeIndex(context.candles1d());

            List<double[]> cleanFeatures = new ArrayList<>();
            List<Double> cleanTargets = new ArrayList<>();
            List<String> featureNames = mlProperties.features();

            for (int i = 0; i < allFeatures.size(); i++) {
                Map<String, Double> featureMap = new HashMap<>(allFeatures.get(i));

                if (!featureMap.isEmpty()) {
                    long closeTime = series.getBar(i).getEndTime().toInstant().toEpochMilli();
                    addHigherTimeframeFeatures(featureMap, closeTime, map1h, map4h, map1d);
                }

                if (!featureMap.isEmpty() && i < targets.size()) {
                    double[] featureArray = new double[featureNames.size()];
                    for (int j = 0; j < featureNames.size(); j++) {
                        featureArray[j] = featureMap.getOrDefault(featureNames.get(j), 0.0);
                    }
                    cleanFeatures.add(featureArray);
                    cleanTargets.add(targets.get(i) ? 1.0 : 0.0);
                }
            }

            if (cleanFeatures.size() < 1000) {
                throw new IllegalStateException("Not enough clean data for " + symbol);
            }

            int splitIndex = (int) (cleanFeatures.size() * 0.8);
            List<double[]> trainFeatures = cleanFeatures.subList(0, splitIndex);
            List<Double> trainTargets = cleanTargets.subList(0, splitIndex);
            List<double[]> testFeatures = cleanFeatures.subList(splitIndex, cleanFeatures.size());
            List<Double> testTargets = cleanTargets.subList(splitIndex, cleanTargets.size());

            XGBoostModel.TrainingResult result = xgBoostModel.train(
                    symbol,
                    trainFeatures, trainTargets,
                    testFeatures, testTargets,
                    featureNames
            );

            trainingResults.put(symbol, result);
            logFeatureImportance(symbol, result.featureImportance());

            LOGGER.info("🚀 XGBoost training completed for {} in {}ms",
                    symbol, System.currentTimeMillis() - startTime);

            return result;
        }).onErrorResume(error -> {
            LOGGER.error("XGBoost training failed for {}: {}", symbol, error.getMessage());
            return Mono.just(new XGBoostModel.TrainingResult(
                    symbol, 0.0, 0.0, 0, 0, new HashMap<>()
            ));
        });
    }

    // Tüm semboller için eğit
    public Mono<Void> trainAllModels(List<String> symbols) {
        LOGGER.info("Starting XGBoost training for {} symbols...", symbols.size());

        return reactor.core.publisher.Flux.fromIterable(symbols)
                .parallel(mlProperties.maxParallelTraining())
                .runOn(reactor.core.scheduler.Schedulers.parallel())
                .flatMap(this::trainModelForSymbol)
                .sequential()
                .collectList()
                .doOnSuccess(results -> {
                    double avgAccuracy = results.stream()
                            .mapToDouble(XGBoostModel.TrainingResult::testAccuracy)
                            .average()
                            .orElse(0.0);

                    int successful = (int) results.stream()
                            .filter(r -> r.testAccuracy() > mlProperties.minTestAccuracy())
                            .count();

                    LOGGER.info("=".repeat(60));
                    LOGGER.info("🎯 XGBOOST TRAINING SUMMARY");
                    LOGGER.info("Total Symbols: {}", symbols.size());
                    LOGGER.info("Successfully Trained: {}", successful);
                    LOGGER.info("Average Accuracy: {:.2f}%", avgAccuracy * 100);
                    LOGGER.info("=".repeat(60));

                    results.stream()
                            .filter(r -> r.testAccuracy() > mlProperties.minTestAccuracy())
                            .sorted((a, b) -> Double.compare(b.testAccuracy(), a.testAccuracy()))
                            .forEach(r -> LOGGER.info("  {}: {:.2f}% ({} samples)",
                                    r.symbol(), r.testAccuracy() * 100, r.trainSamples()));

                    LOGGER.info("=".repeat(60));
                })
                .then();
    }

    // Canlı tahmin yap
    public XGBoostModel.PredictionResult predict(String symbol, BarSeries series,
                                                 TimeframeContext context) {
        if (!xgBoostModel.isModelTrained(symbol)) {
            return new XGBoostModel.PredictionResult(0.5, false, "MODEL_NOT_TRAINED");
        }

        try {
            int lastIndex = series.getBarCount() - 1;
            Map<String, Double> features = featureService.extractFeaturesAtBar(series, lastIndex);

            long closeTime = series.getBar(lastIndex).getEndTime().toInstant().toEpochMilli();
            NavigableMap<Long, Candle> map1h = buildTimeIndex(context.candles1h());
            NavigableMap<Long, Candle> map4h = buildTimeIndex(context.candles4h());
            NavigableMap<Long, Candle> map1d = buildTimeIndex(context.candles1d());
            addHigherTimeframeFeatures(features, closeTime, map1h, map4h, map1d);

            List<String> featureNames = xgBoostModel.getFeatureNames(symbol);
            double[] featureArray = new double[featureNames.size()];

            for (int i = 0; i < featureNames.size(); i++) {
                featureArray[i] = features.getOrDefault(featureNames.get(i), 0.0);
            }

            return xgBoostModel.predict(symbol, featureArray);

        } catch (Exception e) {
            LOGGER.error("Prediction failed for {}: {}", symbol, e.getMessage());
            return new XGBoostModel.PredictionResult(0.5, false, "ERROR");
        }
    }

    // Feature importance'ı logla (Integer tipinde)
    private void logFeatureImportance(String symbol, Map<String, Integer> importance) {
        if (importance.isEmpty()) return;

        LOGGER.debug("📊 Feature Importance for {}:", symbol);
        importance.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(10) // Top 10 features
                .forEach(entry -> LOGGER.debug("  {}: {}", entry.getKey(), entry.getValue()));
    }

    private NavigableMap<Long, Candle> buildTimeIndex(List<Candle> candles) {
        NavigableMap<Long, Candle> index = new TreeMap<>();
        if (candles == null) {
            return index;
        }
        for (Candle candle : candles) {
            index.put(candle.closeTime(), candle);
        }
        return index;
    }

    private void addHigherTimeframeFeatures(Map<String, Double> features,
                                            long closeTime,
                                            NavigableMap<Long, Candle> map1h,
                                            NavigableMap<Long, Candle> map4h,
                                            NavigableMap<Long, Candle> map1d) {
        features.put("HTF_1H_TREND", resolveTrendPercent(map1h, closeTime));
        features.put("HTF_4H_TREND", resolveTrendPercent(map4h, closeTime));
        features.put("HTF_1D_TREND", resolveTrendPercent(map1d, closeTime));
    }

    private double resolveTrendPercent(NavigableMap<Long, Candle> map, long closeTime) {
        if (map == null || map.isEmpty()) {
            return 0.0;
        }
        Map.Entry<Long, Candle> currentEntry = map.floorEntry(closeTime);
        if (currentEntry == null) {
            return 0.0;
        }
        Map.Entry<Long, Candle> prevEntry = map.lowerEntry(currentEntry.getKey());
        if (prevEntry == null) {
            return 0.0;
        }
        double prevClose = prevEntry.getValue().close();
        if (prevClose == 0) {
            return 0.0;
        }
        return (currentEntry.getValue().close() - prevClose) / prevClose * 100;
    }

    // Getter metodları
    public boolean isSymbolTrained(String symbol) {
        return trainingResults.containsKey(symbol);
    }

    public XGBoostModel.TrainingResult getTrainingResult(String symbol) {
        return trainingResults.get(symbol);
    }

    public record TimeframeContext(
            List<Candle> candles1h,
            List<Candle> candles4h,
            List<Candle> candles1d
    ) {}
}
