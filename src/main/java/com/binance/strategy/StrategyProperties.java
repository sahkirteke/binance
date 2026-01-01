package com.binance.strategy;

import java.math.BigDecimal;
import java.util.Optional;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

@Validated
@ConfigurationProperties(prefix = "strategy")
public record StrategyProperties(
		@NotBlank String symbol,
		Optional<@DecimalMin("0.0") BigDecimal> targetPrice,
		@NotNull @DecimalMin("0.0") BigDecimal notionalUsd,
		@NotNull @DecimalMin("0.0") BigDecimal marketQuantity,
		int leverage) {
}
