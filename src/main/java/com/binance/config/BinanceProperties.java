package com.binance.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;

@Validated
@ConfigurationProperties(prefix = "binance")
public record BinanceProperties(
		@NotBlank String baseUrl,
		String apiKey,
		String secretKey,
		long recvWindowMillis) {
}
