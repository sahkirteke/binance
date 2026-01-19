package com.binance.strategy;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({MLProperties.class, StrategyProperties.class})
public class MLConfiguration {
}
