package com.binance.strategy;

import java.math.BigDecimal;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

@Validated
@ConfigurationProperties(prefix = "strategy")
public record StrategyProperties(
		@NotBlank String referenceSymbol,
		@NotBlank String tradeSymbol,
		@Positive int depthLimit,
		@Positive BigDecimal marketQuantity,
		int leverage,
		String positionSide,
		boolean enableOrders) {
}
