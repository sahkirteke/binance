// file: MLProperties.java (XGBoost 3.1.1 için güncellendi)
package com.binance.strategy;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.util.List;
import java.util.Map;

@ConfigurationProperties(prefix = "ml")
public record MLProperties(
        // Temel parametreler
        boolean enabled,
        int historicalBars,
        double predictionThreshold,
        int lookAheadBars,
        boolean trainOnStartup,
        double minTestAccuracy,
        int maxParallelTraining,

        // XGBoost parametreleri
        XGBoostParams xgboost,

        // Özellik listesi
        List<String> features
) {
    public MLProperties {
        if (historicalBars <= 0) historicalBars = 2000;
        if (predictionThreshold <= 0) predictionThreshold = 0.55;
        if (lookAheadBars <= 0) lookAheadBars = 1;
        if (minTestAccuracy <= 0) minTestAccuracy = 0.52;
        if (maxParallelTraining <= 0) maxParallelTraining = 3;
        if (features == null || features.isEmpty()) {
            features = getDefaultFeatures();
        }
        if (xgboost == null) {
            xgboost = new XGBoostParams(100, "hist", 4, 10,
                    "binary:logistic", "logloss", 6, 0.1, 0.8, 0.8,
                    3, 0.1, 1.0, 0.0, 42);
        }
    }

    private List<String> getDefaultFeatures() {
        return List.of(
                "CLOSE", "HIGH_LOW_RANGE", "VOLUME_RATIO_10",
                "EMA_20_DISTANCE", "EMA_50_DISTANCE", "ICHIMOKU_SPAN_DIFF",
                "RSI_14", "RSI_7", "MACD_HISTOGRAM", "MACD_SLOPE",
                "STOCH_K", "STOCH_D", "CCI", "BB_PERCENT_B", "BB_BANDWIDTH",
                "ATR_PERCENT_14", "DONCHIAN_POSITION", "WILLIAMS_R",
                "AWESOME_OSCILLATOR", "RSI_BB_OVERSOLD", "RSI_BB_OVERBOUGHT",
                "TREND_VOLUME_CONFIRMATION", "MTF_TREND_ALIGNMENT"
        );
    }

    // XGBoost parametreleri için nested record
    public record XGBoostParams(
            int rounds,
            String treeMethod,
            int nthread,
            int earlyStoppingRounds,
            String objective,
            String evalMetric,
            int maxDepth,
            double eta,
            double subsample,
            double colsampleBytree,
            int minChildWeight,
            double gamma,
            double lambda,
            double alpha,
            int seed
    ) {}
}