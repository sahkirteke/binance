//// file: MLPredictionService.java (XGBoost entegre)
//package com.binance.strategy;
//
//import org.slf4j.Logger;
//import org.slf4j.LoggerFactory;
//import org.springframework.stereotype.Service;
//import org.ta4j.core.BarSeries;
//
//import java.time.Instant;
//import java.util.*;
//import java.util.concurrent.ConcurrentHashMap;
//
//@Service
//public class MLPredictionService {
//    private static final Logger LOGGER = LoggerFactory.getLogger(MLPredictionService.class);
//
//    private final XGBoostTrainingService xgBoostService;
//    private final TA4JFeatureService featureService;
//    private final MLProperties mlProperties;
//
//    // Her sembol için son N bar
//    private final Map<String, List<Candle>> recentCandles = new ConcurrentHashMap<>();
//    private final int MAX_RECENT_CANDLES = 100;
//
//    // Tahmin geçmişi
//    private final Map<String, List<PredictionRecord>> predictionHistory = new ConcurrentHashMap<>();
//
//    public MLPredictionService(XGBoostTrainingService xgBoostService,
//                               TA4JFeatureService featureService,
//                               MLProperties mlProperties) {
//        this.xgBoostService = xgBoostService;
//        this.featureService = featureService;
//        this.mlProperties = mlProperties;
//    }
//
//    // Yeni bar geldiğinde çağırılacak
//    public void onNewCandle(String symbol, Candle candle) {
//        if (!mlProperties.enabled()) return;
//
//        try {
//            // 1. Bar'ı kaydet
//            recentCandles.computeIfAbsent(symbol, k -> new ArrayList<>())
//                    .add(candle);
//
//            List<Candle> candles = recentCandles.get(symbol);
//            if (candles.size() > MAX_RECENT_CANDLES) {
//                candles.remove(0);
//            }
//
//            // 2. Model eğitilmiş mi kontrol et
//            if (!xgBoostService.isSymbolTrained(symbol)) {
//                LOGGER.debug("Model not trained for {}, skipping prediction", symbol);
//                return;
//            }
//
//            // 3. BarSeries oluştur
//            BarSeries series = featureService.createBarSeries(symbol, candles);
//
//            // 4. XGBoost ile tahmin yap
//            XGBoostModel.PredictionResult prediction = xgBoostService.predict(symbol, series);
//
//            // 5. Kaydet ve logla
//            savePrediction(symbol, prediction);
//            logPrediction(symbol, prediction, candle);
//
//        } catch (Exception e) {
//            LOGGER.warn("ML prediction failed for {}: {}", symbol, e.getMessage());
//        }
//    }
//
//    // XGBoost tahminini logla (İSTEĞİN GİBİ)
//    private void logPrediction(String symbol, XGBoostModel.PredictionResult prediction,
//                               Candle candle) {
//
//        String direction = prediction.willRise() ? "YUKARI ↑" : "AŞAĞI ↓";
//        String confidence = prediction.confidence();
//        double price = candle.close();
//
//        // Sadece yüksek güvenilirliği olan tahminleri logla
//        if (prediction.probability() > 0.65 || prediction.probability() < 0.35) {
//            LOGGER.info("🔥 COIN {} {} (XGBoost {} güven, Fiyat: ${})",
//                    symbol, direction, confidence, price);
//        }
//    }
//
//    private void savePrediction(String symbol, XGBoostModel.PredictionResult prediction) {
//        predictionHistory.computeIfAbsent(symbol, k -> new ArrayList<>())
//                .add(new PredictionRecord(prediction, System.currentTimeMillis()));
//
//        // Geçmişi sınırla
//        List<PredictionRecord> history = predictionHistory.get(symbol);
//        if (history.size() > 1000) {
//            history.subList(0, history.size() - 1000).clear();
//        }
//    }
//
//    // Son tahmini getir
//    public XGBoostModel.PredictionResult getLastPrediction(String symbol) {
//        List<PredictionRecord> history = predictionHistory.get(symbol);
//        if (history == null || history.isEmpty()) {
//            return new XGBoostModel.PredictionResult(0.5, false, "NO_PREDICTION");
//        }
//        return history.get(history.size() - 1).prediction();
//    }
//
//    // Record sınıfı
//    public record PredictionRecord(
//            XGBoostModel.PredictionResult prediction,
//            long timestamp
//    ) {}
//}