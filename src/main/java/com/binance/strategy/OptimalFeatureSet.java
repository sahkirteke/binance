package com.binance.strategy;

import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

// OptimalFeatureSet.java
@Component
public class OptimalFeatureSet {
    
    // Her coin tipi için özelleştirilmiş feature set'leri
    public List<String> getFeaturesForCoin(String symbol) {
        // Tüm coin'ler için temel set
        List<String> baseFeatures = Arrays.asList(
            // Price & Volume
            "CLOSE", "HIGH_LOW_RANGE", "VOLUME_RATIO_10",
            
            // Trend
            "EMA_20_DISTANCE", "EMA_50_DISTANCE",
            
            // Momentum
            "RSI_14", "MACD_HISTOGRAM",
            
            // Volatility
            "BB_PERCENT_B", "BB_BANDWIDTH", "ATR_PERCENT_14",
            
            // Oscillators
            "STOCH_K", "STOCH_D",
            
            // Custom
            "RSI_BB_OVERSOLD", "RSI_BB_OVERBOUGHT",
            "TREND_VOLUME_CONFIRMATION"
        );
        
        // BTC, ETH gibi büyük cap'ler için ekstra
        if (symbol.contains("BTC") || symbol.contains("ETH")) {
            baseFeatures.addAll(Arrays.asList(
                "ICHIMOKU_SPAN_DIFF",
                "DONCHIAN_POSITION",
                "CCI"
            ));
        }
        
        // Altcoin'ler için daha agresif
        if (symbol.contains("USDT") && !symbol.contains("BTC") && !symbol.contains("ETH")) {
            baseFeatures.addAll(Arrays.asList(
                "WILLIAMS_R",
                "AWESOME_OSCILLATOR",
                "MACD_SLOPE"
            ));
        }
        
        return baseFeatures;
    }
}