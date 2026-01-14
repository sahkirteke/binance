//// file: XGBoostTrainingService.java
//package com.binance.strategy;
//
//import com.binance.market.BinanceMarketClient;
//import org.slf4j.Logger;
//import org.slf4j.LoggerFactory;
//import org.springframework.stereotype.Service;
//import org.ta4j.core.BarSeries;
//import reactor.core.publisher.Mono;
//
//import java.util.*;
//import java.util.concurrent.ConcurrentHashMap;
//
//
//public class XGBoostTrainingService {
//    private static final Logger LOGGER = LoggerFactory.getLogger(XGBoostTrainingService.class);
//
//    private final BinanceMarketClient marketClient;
//    private final TA4JFeatureService featureService;
//    private final XGBoostModel xgBoostModel;
//    private final MLProperties mlProperties;
//
//    // Eğitim sonuçları cache'i
//    private final Map<String, XGBoostModel.TrainingResult> trainingResults = new ConcurrentHashMap<>();
//
//    public XGBoostTrainingService(BinanceMarketClient marketClient,
//                                  TA4JFeatureService featureService,
//                                  XGBoostModel xgBoostModel,
//                                  MLProperties mlProperties) {
//        this.marketClient = marketClient;
//        this.featureService = featureService;
//        this.xgBoostModel = xgBoostModel;
//        this.mlProperties = mlProperties;
//    }
//
//    // Sembol için model eğit
//    public Mono<XGBoostModel.TrainingResult> trainModelForSymbol(String symbol) {
//        return marketClient.fetchFuturesKlinesRaw(symbol, "1m", mlProperties.historicalBars())
//                .map(response -> {
//                    // 1. Verileri parse et
//                    List<Candle> candles = parseCandles(response.body(), symbol);
//
//                    if (candles.size() < 1500) {
//                        throw new IllegalStateException("Insufficient data for " + symbol);
//                    }
//
//                    long startTime = System.currentTimeMillis();
//
//                    // 2. TA4J BarSeries oluştur
//                    BarSeries series = featureService.createBarSeries(symbol, candles);
//
//                    // 3. Tüm özellikleri çıkar
//                    List<Map<String, Double>> allFeatures = featureService.extractAllFeatures(series);
//
//                    // 4. Hedef değişkenleri oluştur
//                    List<Boolean> targets = featureService.createTargets(
//                            series,
//                            mlProperties.lookAheadBars(),
//                            0.001 // %0.1 threshold
//                    );
//
//                    // 5. Temiz veri seti oluştur
//                    List<double[]> cleanFeatures = new ArrayList<>();
//                    List<Double> cleanTargets = new ArrayList<>();
//                    List<String> featureNames = new ArrayList<>();
//
//                    // İlk geçerli satırdan feature isimlerini al
//                    for (int i = 0; i < allFeatures.size(); i++) {
//                        Map<String, Double> featureMap = allFeatures.get(i);
//
//                        if (featureNames.isEmpty() && !featureMap.isEmpty()) {
//                            featureNames = new ArrayList<>(featureMap.keySet());
//                        }
//
//                        if (!featureMap.isEmpty() && i < targets.size()) {
//                            double[] featureArray = new double[featureNames.size()];
//                            for (int j = 0; j < featureNames.size(); j++) {
//                                featureArray[j] = featureMap.getOrDefault(featureNames.get(j), 0.0);
//                            }
//                            cleanFeatures.add(featureArray);
//                            cleanTargets.add(targets.get(i) ? 1.0 : 0.0);
//                        }
//                    }
//
//                    if (cleanFeatures.size() < 1000) {
//                        throw new IllegalStateException("Not enough clean data for " + symbol);
//                    }
//
//                    // 6. Train/Test split (%80/%20)
//                    int splitIndex = (int) (cleanFeatures.size() * 0.8);
//                    List<double[]> trainFeatures = cleanFeatures.subList(0, splitIndex);
//                    List<Double> trainTargets = cleanTargets.subList(0, splitIndex);
//                    List<double[]> testFeatures = cleanFeatures.subList(splitIndex, cleanFeatures.size());
//                    List<Double> testTargets = cleanTargets.subList(splitIndex, cleanTargets.size());
//
//                    // 7. XGBoost modelini eğit
//                    XGBoostModel.TrainingResult result = xgBoostModel.train(
//                            symbol,
//                            trainFeatures, trainTargets,
//                            testFeatures, testTargets,
//                            featureNames
//                    );
//
//                    // 8. Cache'e kaydet
//                    trainingResults.put(symbol, result);
//
//                    // 9. Feature importance logla
//                    logFeatureImportance(symbol, result.featureImportance());
//
//                    LOGGER.info("🚀 XGBoost training completed for {} in {}ms",
//                            symbol, System.currentTimeMillis() - startTime);
//
//                    return result;
//
//                })
//                .onErrorResume(error -> {
//                    LOGGER.error("XGBoost training failed for {}: {}", symbol, error.getMessage());
//                    return Mono.just(new XGBoostModel.TrainingResult(
//                            symbol, 0.0, 0.0, 0, 0, new HashMap<>()
//                    ));
//                });
//    }
//
//    // Tüm semboller için eğit
//    public Mono<Void> trainAllModels(List<String> symbols) {
//        LOGGER.info("Starting XGBoost training for {} symbols...", symbols.size());
//
//        return reactor.core.publisher.Flux.fromIterable(symbols)
//                .parallel(mlProperties.maxParallelTraining())
//                .runOn(reactor.core.scheduler.Schedulers.parallel())
//                .flatMap(this::trainModelForSymbol)
//                .sequential()
//                .collectList()
//                .doOnSuccess(results -> {
//                    // İstatistikleri hesapla
//                    double avgAccuracy = results.stream()
//                            .mapToDouble(XGBoostModel.TrainingResult::testAccuracy)
//                            .average()
//                            .orElse(0.0);
//
//                    int successful = (int) results.stream()
//                            .filter(r -> r.testAccuracy() > mlProperties.minTestAccuracy())
//                            .count();
//
//                    // Logla
//                    LOGGER.info("=".repeat(60));
//                    LOGGER.info("🎯 XGBOOST TRAINING SUMMARY");
//                    LOGGER.info("Total Symbols: {}", symbols.size());
//                    LOGGER.info("Successfully Trained: {}", successful);
//                    LOGGER.info("Average Accuracy: {:.2f}%", avgAccuracy * 100);
//                    LOGGER.info("=".repeat(60));
//
//                    // Her sembol için sonuç
//                    results.stream()
//                            .filter(r -> r.testAccuracy() > mlProperties.minTestAccuracy())
//                            .sorted((a, b) -> Double.compare(b.testAccuracy(), a.testAccuracy()))
//                            .forEach(r -> LOGGER.info("  {}: {:.2f}% ({} samples)",
//                                    r.symbol(), r.testAccuracy() * 100, r.trainSamples()));
//
//                    LOGGER.info("=".repeat(60));
//                })
//                .then();
//    }
//
//    // Canlı tahmin yap
//    public XGBoostModel.PredictionResult predict(String symbol, BarSeries series) {
//        if (!xgBoostModel.isModelTrained(symbol)) {
//            return new XGBoostModel.PredictionResult(0.5, false, "MODEL_NOT_TRAINED");
//        }
//
//        try {
//            int lastIndex = series.getBarCount() - 1;
//            Map<String, Double> features = featureService.extractFeaturesAtBar(series, lastIndex);
//
//            List<String> featureNames = xgBoostModel.getFeatureNames(symbol);
//            double[] featureArray = new double[featureNames.size()];
//
//            for (int i = 0; i < featureNames.size(); i++) {
//                featureArray[i] = features.getOrDefault(featureNames.get(i), 0.0);
//            }
//
//            return xgBoostModel.predict(symbol, featureArray);
//
//        } catch (Exception e) {
//            LOGGER.error("Prediction failed for {}: {}", symbol, e.getMessage());
//            return new XGBoostModel.PredictionResult(0.5, false, "ERROR");
//        }
//    }
//
//    // Feature importance'ı logla (Integer tipinde)
//    private void logFeatureImportance(String symbol, Map<String, Integer> importance) {
//        if (importance.isEmpty()) return;
//
//        LOGGER.debug("📊 Feature Importance for {}:", symbol);
//        importance.entrySet().stream()
//                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
//                .limit(10) // Top 10 features
//                .forEach(entry -> LOGGER.debug("  {}: {}", entry.getKey(), entry.getValue()));
//    }
//
//    private List<Candle> parseCandles(String json, String symbol) {
//        List<Candle> candles = new ArrayList<>();
//        try {
//            com.fasterxml.jackson.databind.JsonNode root =
//                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
//
//            if (root.isArray()) {
//                for (com.fasterxml.jackson.databind.JsonNode node : root) {
//                    Candle candle = new Candle(
//                            node.get(1).asDouble(),
//                            node.get(2).asDouble(),
//                            node.get(3).asDouble(),
//                            node.get(4).asDouble(),
//                            node.get(5).asDouble(),
//                            node.get(6).asLong()
//                    );
//                    candles.add(candle);
//                }
//            }
//        } catch (Exception e) {
//            LOGGER.error("Failed to parse candles for {}", symbol, e);
//        }
//        return candles;
//    }
//
//    // Getter metodları
//    public boolean isSymbolTrained(String symbol) {
//        return trainingResults.containsKey(symbol);
//    }
//
//    public XGBoostModel.TrainingResult getTrainingResult(String symbol) {
//        return trainingResults.get(symbol);
//    }
//}