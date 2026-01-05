package com.binance.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.unit.DataSize;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;

import com.fasterxml.jackson.databind.ObjectMapper;

@Configuration
public class WebClientConfig {

	@Bean
	public WebClient.Builder webClientBuilder() {
		return WebClient.builder();
	}

	@Bean
	public WebClient binanceWebClient(BinanceProperties properties, WebClient.Builder builder, DataSize maxInMemorySize) {
		String baseUrl = properties.useTestnet() ? properties.testnetBaseUrl() : properties.baseUrl();
		ExchangeStrategies strategies = exchangeStrategies(maxInMemorySize);
		return builder
				.baseUrl(baseUrl)
				.defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
				.exchangeStrategies(strategies)
				.build();
	}

	@Bean
	public WebClient spotWebClient(BinanceProperties properties, WebClient.Builder builder, DataSize maxInMemorySize) {
		ExchangeStrategies strategies = exchangeStrategies(maxInMemorySize);
		return builder
				.baseUrl(properties.baseUrl())
				.defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
				.exchangeStrategies(strategies)
				.build();
	}

	ExchangeStrategies exchangeStrategies(DataSize maxInMemorySize) {
		return ExchangeStrategies.builder()
				.codecs(configurer -> configurer.defaultCodecs()
						.maxInMemorySize((int) maxInMemorySize.toBytes()))
				.build();
	}

	@Bean
	public DataSize maxInMemorySize(@Value("${spring.codec.max-in-memory-size:5MB}") DataSize maxInMemorySize) {
		return maxInMemorySize;
	}

	@Bean
	public ObjectMapper objectMapper() {
		return new ObjectMapper().findAndRegisterModules();
	}
}
