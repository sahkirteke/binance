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

    // Her sembol için son N bar (interval bazında)
    private final Map<String, Map<String, Deque<Candle>>> recentCandles = new ConcurrentHashMap<>();
    private static final int MAX_RECENT_CANDLES = 500;

    // Tahmin geçmişi
    private final Map<String, List<PredictionRecord>> predictionHistory = new ConcurrentHashMap<>();

    public MLPredictionService(XGBoostTrainingService xgBoostService,
                               TA4JFeatureService featureService,
                               MLProperties mlProperties) {
        this.xgBoostService = xgBoostService;
        this.featureService = featureService;
        this.mlProperties = mlProperties;
    }

    // Yeni bar geldiğinde çağırılacak
    public void onNewCandle(String symbol, String interval, Candle candle) {
        if (!mlProperties.enabled()) return;

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

        try {
            if (!xgBoostService.isSymbolTrained(symbol)) {
                LOGGER.debug("Model not trained for {}, skipping prediction", symbol);
                return;
            }

            List<Candle> baseCandles = new ArrayList<>(candles);
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

            savePrediction(symbol, prediction);
            logPrediction(symbol, prediction, candle);

        } catch (Exception e) {
            LOGGER.warn("ML prediction failed for {}: {}", symbol, e.getMessage());
        }
    }

    // XGBoost tahminini logla (İSTEĞİN GİBİ)
    private void logPrediction(String symbol, XGBoostModel.PredictionResult prediction,
                               Candle candle) {

        String direction = prediction.willRise() ? "YUKARI ↑" : "AŞAĞI ↓";
        String confidence = prediction.confidence();
        double price = candle.close();

        // Sadece yüksek güvenilirliği olan tahminleri logla
        if (prediction.probability() > 0.65 || prediction.probability() < 0.35) {
            LOGGER.info("🔥 COIN {} {} (XGBoost {} güven, Fiyat: ${})",
                    symbol, direction, confidence, price);
        }
    }

    private void savePrediction(String symbol, XGBoostModel.PredictionResult prediction) {
        predictionHistory.computeIfAbsent(symbol, k -> new ArrayList<>())
                .add(new PredictionRecord(prediction, System.currentTimeMillis()));

        // Geçmişi sınırla
        List<PredictionRecord> history = predictionHistory.get(symbol);
        if (history.size() > 1000) {
            history.subList(0, history.size() - 1000).clear();
        }
    }

    // Son tahmini getir
    public XGBoostModel.PredictionResult getLastPrediction(String symbol) {
        List<PredictionRecord> history = predictionHistory.get(symbol);
        if (history == null || history.isEmpty()) {
            return new XGBoostModel.PredictionResult(0.5, false, "NO_PREDICTION");
        }
        return history.get(history.size() - 1).prediction();
    }

    // Record sınıfı
    public record PredictionRecord(
            XGBoostModel.PredictionResult prediction,
            long timestamp
    ) {}
}
