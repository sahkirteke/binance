//package com.binance.strategy;
//
//import org.slf4j.Logger;
//import org.slf4j.LoggerFactory;
//import org.springframework.stereotype.Service;
//import org.ta4j.core.*;
//import org.ta4j.core.indicators.*;
//import org.ta4j.core.indicators.bollinger.*;
//import org.ta4j.core.indicators.helpers.*;
//import org.ta4j.core.indicators.statistics.StandardDeviationIndicator;
//import org.ta4j.core.num.DoubleNum;
//
//import java.time.Duration;
//import java.time.ZoneId;
//import java.time.ZonedDateTime;
//import java.util.*;
//@Service
//public class TA4JFeatureService {
//    private static final Logger LOGGER = LoggerFactory.getLogger(TA4JFeatureService.class);
//
//    // 1. BAR SERIES OLUŞTURMA
//    public BarSeries createBarSeries(String symbol, List<Candle> candles) {
//        BarSeries series = new BaseBarSeriesBuilder()
//                .withName(symbol)
//                .withNumTypeOf(DoubleNum.class)
//                .build();
//
//        for (Candle candle : candles) {
//            ZonedDateTime time = ZonedDateTime.ofInstant(
//                    java.time.Instant.ofEpochMilli(candle.closeTime()),
//                    ZoneId.systemDefault()
//            );
//
//            try {
//                // TA4J 0.15 için 9 parametreli constructor
//                Bar bar = new BaseBar(
//                        Duration.ofMinutes(1),
//                        time,
//                        series.numOf(candle.open()),
//                        series.numOf(candle.high()),
//                        series.numOf(candle.low()),
//                        series.numOf(candle.close()),
//                        series.numOf(candle.volume()),
//                        series.numOf(0.0),  // amount
//                        series.numOf(1.0).longValue()   // trades
//                );
//
//                series.addBar(bar);
//
//            } catch (Exception e) {
//                LOGGER.warn("Failed to create bar for {}: {}", symbol, e.getMessage());
//            }
//        }
//
//        LOGGER.debug("Created BarSeries for {} with {} bars", symbol, series.getBarCount());
//        return series;
//    }
//
//    // 2. TEK BAR İÇİN ÖZELLİK ÇIKARMA
//    public Map<String, Double> extractFeaturesAtBar(BarSeries series, int index) {
//        Map<String, Double> features = new HashMap<>();
//
//        if (series == null || series.getBarCount() == 0 || index < 50) {
//            return features;
//        }
//
//        try {
//            double close = series.getBar(index).getClosePrice().doubleValue();
//            double high = series.getBar(index).getHighPrice().doubleValue();
//            double low = series.getBar(index).getLowPrice().doubleValue();
//            double volume = series.getBar(index).getVolume().doubleValue();
//
//            // A. FİYAT ve HACİM ÖZELLİKLERİ
//            features.put("CLOSE", close);
//            features.put("HIGH_LOW_RANGE", (high - low) / close * 100);
//
//            // B. HACİM GÖSTERGELERİ
//            if (index >= 10) {
//                VolumeIndicator volumeIndicator = new VolumeIndicator(series);
//                SMAIndicator volumeSma = new SMAIndicator(volumeIndicator, 10);
//                double currentVol = volumeIndicator.getValue(index).doubleValue();
//                double avgVol = volumeSma.getValue(index).doubleValue();
//                features.put("VOLUME_RATIO_10", currentVol / avgVol);
//            }
//
//            // C. RSI (7 ve 14)
//            if (index >= 14) {
//                RSIIndicator rsi14 = new RSIIndicator(new ClosePriceIndicator(series), 14);
//                features.put("RSI_14", rsi14.getValue(index).doubleValue());
//            }
//
//            if (index >= 7) {
//                RSIIndicator rsi7 = new RSIIndicator(new ClosePriceIndicator(series), 7);
//                features.put("RSI_7", rsi7.getValue(index).doubleValue());
//            }
//
//            // D. MACD
//            if (index >= 26) {
//                MACDIndicator macd = new MACDIndicator(new ClosePriceIndicator(series));
//                EMAIndicator macdSignal = new EMAIndicator(macd, 9);
//
//                double macdValue = macd.getValue(index).doubleValue();
//                double signalValue = macdSignal.getValue(index).doubleValue();
//                double histogram = macdValue - signalValue;
//
//                features.put("MACD_VALUE", macdValue);
//                features.put("MACD_HISTOGRAM", histogram);
//
//                // MACD Slope
//                if (index > 0) {
//                    double prevMacd = macd.getValue(index - 1).doubleValue();
//                    features.put("MACD_SLOPE", macdValue - prevMacd);
//                }
//            }
//
//            // E. BOLLINGER BANDS (DOĞRU KULLANIM - TA4J 0.15+)
//            if (index >= 20) {
//                ClosePriceIndicator closePrice = new ClosePriceIndicator(series);
//
//                // Önce SMA'yı oluştur
//                SMAIndicator sma20 = new SMAIndicator(closePrice, 20);
//
//                // Middle band (SMA'dan türetilir)
//                BollingerBandsMiddleIndicator bbMiddle =
//                        new BollingerBandsMiddleIndicator(sma20);
//
//                // Standard deviation (20 period)
//                StandardDeviationIndicator stdDev =
//                        new StandardDeviationIndicator(closePrice, 20);
//
//                // Upper and lower bands
//                BollingerBandsUpperIndicator bbUpper =
//                        new BollingerBandsUpperIndicator(bbMiddle, stdDev);
//                BollingerBandsLowerIndicator bbLower =
//                        new BollingerBandsLowerIndicator(bbMiddle, stdDev);
//
//                double upper = bbUpper.getValue(index).doubleValue();
//                double lower = bbLower.getValue(index).doubleValue();
//                double middle = bbMiddle.getValue(index).doubleValue();
//
//                // BB %B
//                if (upper - lower != 0) {
//                    features.put("BB_PERCENT_B", (close - lower) / (upper - lower));
//                }
//
//                // BB Width
//                features.put("BB_BANDWIDTH", (upper - lower) / middle);
//            }
//
//            // F. EMA'LAR
//            if (index >= 10) {
//                EMAIndicator ema10 = new EMAIndicator(new ClosePriceIndicator(series), 10);
//                double ema10Value = ema10.getValue(index).doubleValue();
//                features.put("EMA_10_DISTANCE", (close - ema10Value) / close * 100);
//            }
//
//            if (index >= 20) {
//                EMAIndicator ema20 = new EMAIndicator(new ClosePriceIndicator(series), 20);
//                double ema20Value = ema20.getValue(index).doubleValue();
//                features.put("EMA_20_DISTANCE", (close - ema20Value) / close * 100);
//            }
//
//            if (index >= 50) {
//                EMAIndicator ema50 = new EMAIndicator(new ClosePriceIndicator(series), 50);
//                double ema50Value = ema50.getValue(index).doubleValue();
//                features.put("EMA_50_DISTANCE", (close - ema50Value) / close * 100);
//            }
//
//            // G. ATR (VOLATİLİTE)
//            if (index >= 14) {
//                ATRIndicator atr = new ATRIndicator(series, 14);
//                double atrValue = atr.getValue(index).doubleValue();
//                features.put("ATR_PERCENT_14", atrValue / close * 100);
//            }
//
//            // H. FİYAT DEĞİŞİMLERİ
//            if (index > 0) {
//                double prevClose = series.getBar(index - 1).getClosePrice().doubleValue();
//                features.put("PRICE_CHANGE_1", (close - prevClose) / prevClose * 100);
//            }
//
//            if (index >= 5) {
//                double prevClose5 = series.getBar(index - 5).getClosePrice().doubleValue();
//                features.put("PRICE_CHANGE_5", (close - prevClose5) / prevClose5 * 100);
//            }
//
//            // I. STOCHASTIC OSCILLATOR
//            if (index >= 14) {
//                StochasticOscillatorKIndicator stochK =
//                        new StochasticOscillatorKIndicator(series, 14);
//                StochasticOscillatorDIndicator stochD =
//                        new StochasticOscillatorDIndicator(stochK);
//
//                features.put("STOCH_K", stochK.getValue(index).doubleValue());
//                features.put("STOCH_D", stochD.getValue(index).doubleValue());
//            }
//
//            // J. CCI (COMMODITY CHANNEL INDEX)
//            if (index >= 20) {
//                CCIIndicator cci = new CCIIndicator(series, 20);
//                features.put("CCI", cci.getValue(index).doubleValue());
//            }
//
//            // K. WILLIAMS %R
//            if (index >= 14) {
//                WilliamsRIndicator williamsR = new WilliamsRIndicator(series, 14);
//                features.put("WILLIAMS_R", williamsR.getValue(index).doubleValue());
//            }
//
//            // L. AWESOME OSCILLATOR
//            if (index >= 34) {
//                AwesomeOscillatorIndicator ao = new AwesomeOscillatorIndicator(series);
//                features.put("AWESOME_OSCILLATOR", ao.getValue(index).doubleValue());
//            }
//
//            // M. DONCHIAN CHANNELS
//            if (index >= 20) {
//                double highestHigh = getHighestHigh(series, index, 20);
//                double lowestLow = getLowestLow(series, index, 20);
//                double donchianPosition = (close - lowestLow) / (highestHigh - lowestLow);
//                features.put("DONCHIAN_POSITION", donchianPosition);
//            }
//
//            // N. ÖZEL KOMBİNASYONLAR
//            if (features.containsKey("RSI_14") && features.containsKey("BB_PERCENT_B")) {
//                double rsi = features.get("RSI_14");
//                double bbPercent = features.get("BB_PERCENT_B");
//
//                features.put("RSI_BB_OVERSOLD",
//                        (rsi < 30 && bbPercent < 0.2) ? 1.0 : 0.0);
//
//                features.put("RSI_BB_OVERBOUGHT",
//                        (rsi > 70 && bbPercent > 0.8) ? 1.0 : 0.0);
//            }
//
//            // Trend + Volume kombinasyonu
//            if (features.containsKey("EMA_20_DISTANCE") && features.containsKey("VOLUME_RATIO_10")) {
//                double trendStrength = Math.abs(features.get("EMA_20_DISTANCE"));
//                double volumeRatio = features.get("VOLUME_RATIO_10");
//
//                features.put("TREND_VOLUME_CONFIRMATION",
//                        (trendStrength > 1.0 && volumeRatio > 1.2) ? 1.0 : 0.0);
//            }
//
//            // Multi-timeframe trend alignment
//            if (index >= 15) {
//                double change5 = (close - series.getBar(index - 5).getClosePrice().doubleValue())
//                        / series.getBar(index - 5).getClosePrice().doubleValue() * 100;
//
//                double change15 = (close - series.getBar(index - 15).getClosePrice().doubleValue())
//                        / series.getBar(index - 15).getClosePrice().doubleValue() * 100;
//
//                double mtfAlignment = 0.0;
//                if (change5 > 0 && change15 > 0) {
//                    mtfAlignment = 1.0;  // Both up
//                } else if (change5 < 0 && change15 < 0) {
//                    mtfAlignment = -1.0; // Both down
//                }
//                features.put("MTF_TREND_ALIGNMENT", mtfAlignment);
//            }
//
//        } catch (Exception e) {
//            LOGGER.warn("Feature extraction failed at index {}: {}", index, e.getMessage());
//        }
//
//        return features;
//    }
//
//    // 3. TÜM BAR'LAR İÇİN ÖZELLİK ÇIKARMA
//    public List<Map<String, Double>> extractAllFeatures(BarSeries series) {
//        List<Map<String, Double>> allFeatures = new ArrayList<>();
//
//        for (int i = 0; i < series.getBarCount(); i++) {
//            allFeatures.add(extractFeaturesAtBar(series, i));
//        }
//
//        return allFeatures;
//    }
//
//    // 4. HEDEF DEĞİŞKEN OLUŞTURMA
//    public List<Boolean> createTargets(BarSeries series, int lookAheadBars, double thresholdPercent) {
//        List<Boolean> targets = new ArrayList<>();
//
//        for (int i = 0; i < series.getBarCount() - lookAheadBars; i++) {
//            double currentClose = series.getBar(i).getClosePrice().doubleValue();
//            double futureClose = series.getBar(i + lookAheadBars).getClosePrice().doubleValue();
//
//            // thresholdPercent kadar artış var mı?
//            boolean willRise = ((futureClose - currentClose) / currentClose * 100) > thresholdPercent;
//            targets.add(willRise);
//        }
//
//        // Eksik target'lar için false ekle
//        while (targets.size() < series.getBarCount()) {
//            targets.add(false);
//        }
//
//        return targets;
//    }
//
//    // 5. YARDIMCI METODLAR
//
//    // Belirli period'daki en yüksek high'ı bul
//    private double getHighestHigh(BarSeries series, int endIndex, int period) {
//        double highest = Double.MIN_VALUE;
//        int start = Math.max(0, endIndex - period + 1);
//
//        for (int i = start; i <= endIndex; i++) {
//            highest = Math.max(highest, series.getBar(i).getHighPrice().doubleValue());
//        }
//
//        return highest;
//    }
//
//    // Belirli period'daki en düşük low'u bul
//    private double getLowestLow(BarSeries series, int endIndex, int period) {
//        double lowest = Double.MAX_VALUE;
//        int start = Math.max(0, endIndex - period + 1);
//
//        for (int i = start; i <= endIndex; i++) {
//            lowest = Math.min(lowest, series.getBar(i).getLowPrice().doubleValue());
//        }
//
//        return lowest;
//    }
//
//    // 6. ÖZELLİK LİSTESİ GETTER
//    public List<String> getDefaultFeatureNames() {
//        return Arrays.asList(
//                "CLOSE", "HIGH_LOW_RANGE", "VOLUME_RATIO_10",
//                "RSI_14", "RSI_7", "MACD_HISTOGRAM", "MACD_SLOPE",
//                "BB_PERCENT_B", "BB_BANDWIDTH",
//                "EMA_10_DISTANCE", "EMA_20_DISTANCE", "EMA_50_DISTANCE",
//                "ATR_PERCENT_14", "PRICE_CHANGE_1", "PRICE_CHANGE_5",
//                "STOCH_K", "STOCH_D", "CCI", "WILLIAMS_R",
//                "AWESOME_OSCILLATOR", "DONCHIAN_POSITION",
//                "RSI_BB_OVERSOLD", "RSI_BB_OVERBOUGHT",
//                "TREND_VOLUME_CONFIRMATION", "MTF_TREND_ALIGNMENT"
//        );
//    }
//
//    // 7. ÖZELLİKLERİ DOUBLE ARRAY'E ÇEVİRME
//    public double[] convertFeaturesToArray(Map<String, Double> features, List<String> featureNames) {
//        double[] array = new double[featureNames.size()];
//
//        for (int i = 0; i < featureNames.size(); i++) {
//            String featureName = featureNames.get(i);
//            array[i] = features.getOrDefault(featureName, 0.0);
//        }
//
//        return array;
//    }
//
//    // 8. ÖZELLİKLERİ NORMALİZE ETME (opsiyonel)
//    public Map<String, Double> normalizeFeatures(Map<String, Double> features,
//                                                 Map<String, Double> means,
//                                                 Map<String, Double> stds) {
//        Map<String, Double> normalized = new HashMap<>();
//
//        for (Map.Entry<String, Double> entry : features.entrySet()) {
//            String key = entry.getKey();
//            Double value = entry.getValue();
//
//            Double mean = means.get(key);
//            Double std = stds.get(key);
//
//            if (mean != null && std != null && std > 0) {
//                normalized.put(key, (value - mean) / std);
//            } else {
//                normalized.put(key, value);
//            }
//        }
//
//        return normalized;
//    }
//}