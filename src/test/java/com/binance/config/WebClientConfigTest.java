package com.binance.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.core.ResolvableType;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.MediaType;
import org.springframework.http.codec.DecoderHttpMessageReader;
import org.springframework.http.codec.json.Jackson2JsonDecoder;
import org.springframework.util.unit.DataSize;

import com.fasterxml.jackson.databind.JsonNode;

import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

class WebClientConfigTest {

	@Test
	void largePayloadDecodesWithConfiguredLimit() {
		WebClientConfig config = new WebClientConfig();
		DataSize maxInMemorySize = DataSize.ofMegabytes(5);
		Jackson2JsonDecoder decoder = config.exchangeStrategies(maxInMemorySize).messageReaders().stream()
				.filter(reader -> reader instanceof DecoderHttpMessageReader<?>)
				.map(reader -> ((DecoderHttpMessageReader<?>) reader).getDecoder())
				.filter(Jackson2JsonDecoder.class::isInstance)
				.map(Jackson2JsonDecoder.class::cast)
				.findFirst()
				.orElseThrow();

		String json = buildLargeJson();
		DefaultDataBufferFactory bufferFactory = new DefaultDataBufferFactory();
		Flux<JsonNode> decoded = decoder.decode(Flux.just(bufferFactory.wrap(json.getBytes(StandardCharsets.UTF_8))),
				ResolvableType.forClass(JsonNode.class),
				MediaType.APPLICATION_JSON,
				Map.of());

		StepVerifier.create(decoded)
				.expectNextMatches(node -> node.has("symbols"))
				.verifyComplete();

		assertThat(json.length()).isGreaterThan(256_000);
	}

	private String buildLargeJson() {
		StringBuilder builder = new StringBuilder("{\"symbols\":[");
		while (builder.length() < 300_000) {
			builder.append("{\"symbol\":\"TESTUSDT\",\"filters\":[]},");
		}
		builder.append("{\"symbol\":\"LAST\",\"filters\":[]}]}");
		return builder.toString();
	}
}
