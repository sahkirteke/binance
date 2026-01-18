package com.binance.strategy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;

@Component
public class XGBoostStartupRunner implements CommandLineRunner {
    private static final Logger LOGGER = LoggerFactory.getLogger(XGBoostStartupRunner.class);

    private final XGBoostTrainingService trainingService;
    private final MLProperties mlProperties;
    private final StrategyProperties strategyProperties;

    public XGBoostStartupRunner(XGBoostTrainingService trainingService,
                                MLProperties mlProperties,
                                StrategyProperties strategyProperties) {
        this.trainingService = trainingService;
        this.mlProperties = mlProperties;
        this.strategyProperties = strategyProperties;
    }

    @Override
    public void run(String... args) {
        if (!mlProperties.enabled() || !mlProperties.trainOnStartup()) {
            LOGGER.info("XGBoost training disabled or not configured for startup");
            return;
        }

        LOGGER.info("=".repeat(60));
        LOGGER.info("🤖 XGBOOST 3.1.1 AI TRADING SYSTEM INITIALIZATION");
        LOGGER.info("Symbols: {}", strategyProperties.resolvedTradeSymbols().size());
        LOGGER.info("Training will start in 5 seconds...");
        LOGGER.info("=".repeat(60));

        List<String> symbols = strategyProperties.resolvedTradeSymbols();

        Mono.delay(Duration.ofSeconds(5))
                .doOnSubscribe(s -> LOGGER.info("⏰ Starting XGBoost training after 5 second delay..."))
                .then(trainingService.trainAllModels(symbols))
                .doOnTerminate(() -> {
                    LOGGER.info("=".repeat(60));
                    LOGGER.info("✅ XGBOOST AI SYSTEM READY FOR PREDICTIONS");
                    LOGGER.info("=".repeat(60));
                })
                .subscribe(
                        null,
                        error -> LOGGER.error("❌ XGBoost training failed: {}", error.getMessage(), error),
                        () -> LOGGER.info("🎯 XGBoost training completed successfully")
                );
    }
}
