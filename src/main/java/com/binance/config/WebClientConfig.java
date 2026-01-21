package com.binance.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.unit.DataSize;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;

import io.netty.channel.ChannelOption;
import reactor.netty.http.client.HttpClient;
import java.time.Duration;

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
		ReactorClientHttpConnector connector = new ReactorClientHttpConnector(configureHttpClient(properties));
		return builder
				.baseUrl(baseUrl)
				.defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
				.clientConnector(connector)
				.exchangeStrategies(strategies)
				.build();
	}

	@Bean
	public WebClient spotWebClient(BinanceProperties properties, WebClient.Builder builder, DataSize maxInMemorySize) {
		ExchangeStrategies strategies = exchangeStrategies(maxInMemorySize);
		ReactorClientHttpConnector connector = new ReactorClientHttpConnector(configureHttpClient(properties));
		return builder
				.baseUrl(properties.baseUrl())
				.defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
				.clientConnector(connector)
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

	private HttpClient configureHttpClient(BinanceProperties properties) {
		int connectTimeout = properties.connectTimeoutMs() > 0 ? properties.connectTimeoutMs() : 5000;
		long responseTimeout = properties.responseTimeoutMs() > 0 ? properties.responseTimeoutMs() : 10000;
		long handshakeTimeout = properties.handshakeTimeoutMs() > 0 ? properties.handshakeTimeoutMs() : 10000;
		return HttpClient.create()
				.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeout)
				.responseTimeout(Duration.ofMillis(responseTimeout))
				.secure(ssl -> ssl.handshakeTimeout(Duration.ofMillis(handshakeTimeout)));
	}

	@Bean
	public ObjectMapper objectMapper() {
		return new ObjectMapper().findAndRegisterModules();
	}
}
