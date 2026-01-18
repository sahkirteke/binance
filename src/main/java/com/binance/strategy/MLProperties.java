package com.binance.strategy;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

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

        // CSV kaynak ayarları
        CsvConfig csv,

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
        if (csv == null) {
            csv = new CsvConfig(
                    "data",
                    "{symbol}_{interval}.csv",
                    ",",
                    true,
                    "open",
                    "high",
                    "low",
                    "close",
                    "volume",
                    "close_time",
                    "MILLIS",
                    1,
                    2,
                    3,
                    4,
                    5,
                    6
            );
        }
    }

    private List<String> getDefaultFeatures() {
        return List.of(
                "CLOSE", "HIGH_LOW_RANGE", "VOLUME_RATIO_10",
                "RSI_14", "RSI_7", "MACD_VALUE", "MACD_HISTOGRAM", "MACD_SLOPE",
                "BB_PERCENT_B", "BB_BANDWIDTH",
                "EMA_10_DISTANCE", "EMA_20_DISTANCE", "EMA_50_DISTANCE",
                "ATR_PERCENT_14", "PRICE_CHANGE_1", "PRICE_CHANGE_5",
                "STOCH_K", "STOCH_D", "CCI", "WILLIAMS_R",
                "AWESOME_OSCILLATOR", "DONCHIAN_POSITION",
                "RSI_BB_OVERSOLD", "RSI_BB_OVERBOUGHT",
                "TREND_VOLUME_CONFIRMATION", "MTF_TREND_ALIGNMENT",
                "HTF_1H_TREND", "HTF_4H_TREND", "HTF_1D_TREND"
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

    public record CsvConfig(
            String dataDir,
            String filePattern,
            String delimiter,
            boolean hasHeader,
            String openColumn,
            String highColumn,
            String lowColumn,
            String closeColumn,
            String volumeColumn,
            String closeTimeColumn,
            String closeTimeUnit,
            int openIndex,
            int highIndex,
            int lowIndex,
            int closeIndex,
            int volumeIndex,
            int closeTimeIndex
    ) {}
}
