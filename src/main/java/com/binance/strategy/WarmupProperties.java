package com.binance.strategy;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "warmup")
public record WarmupProperties(
		boolean enabled,
		int candles1m,
		int candles5m,
		int concurrency,
		boolean logDecisions,
		int decisionLogEvery,
		long paperGraceSeconds) {
}
