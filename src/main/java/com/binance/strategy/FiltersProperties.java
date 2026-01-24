package com.binance.strategy;

import java.math.BigDecimal;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "filters")
public record FiltersProperties(
		boolean paperFallback,
		BigDecimal paperQtyStep) {
}
