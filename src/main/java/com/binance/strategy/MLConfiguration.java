// file: MLConfiguration.java (güncelle)
package com.binance.strategy;

import com.binance.market.BinanceMarketClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({MLProperties.class, StrategyProperties.class})
public class MLConfiguration {
    

    
    @Bean
    public XGBoostModel xgBoostModel() {
        return new XGBoostModel();
    }
    
    @Bean
    public XGBoostTrainingService xgBoostTrainingService(
            BinanceMarketClient marketClient,
            TA4JFeatureService featureService,
            XGBoostModel xgBoostModel,
            MLProperties mlProperties) {
        return new XGBoostTrainingService(
            marketClient, featureService, xgBoostModel, mlProperties
        );
    }
    
    @Bean
    public MLPredictionService mlPredictionService(  // ← BU BEAN'I EKLE
            XGBoostTrainingService xgBoostTrainingService,
            TA4JFeatureService featureService,
            MLProperties mlProperties) {
        return new MLPredictionService(
            xgBoostTrainingService, featureService, mlProperties
        );
    }
    
    @Bean
    public XGBoostStartupRunner xgBoostStartupRunner(
            XGBoostTrainingService trainingService,
            MLProperties mlProperties,
            StrategyProperties strategyProperties) {
        return new XGBoostStartupRunner(
            trainingService, mlProperties, strategyProperties
        );
    }
}