package com.binance.exchange;

import java.math.BigDecimal;
import java.time.Instant;

import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import com.binance.config.BinanceProperties;
import com.binance.exchange.dto.OrderResponse;

import reactor.core.publisher.Mono;

@Component
public class BinanceFuturesOrderClient {

	private final WebClient binanceWebClient;
	private final BinanceProperties properties;
	private final SignatureUtil signatureUtil;

	public BinanceFuturesOrderClient(WebClient binanceWebClient, BinanceProperties properties, SignatureUtil signatureUtil) {
		this.binanceWebClient = binanceWebClient;
		this.properties = properties;
		this.signatureUtil = signatureUtil;
	}

	public Mono<OrderResponse> placeMarketOrder(String symbol, String side, BigDecimal quantity, String positionSide) {
		if (properties.apiKey() == null || properties.apiKey().isBlank()
				|| properties.secretKey() == null || properties.secretKey().isBlank()) {
			return Mono.error(new IllegalStateException(
					"Binance API key/secret is not configured. Set BINANCE_API_KEY and BINANCE_SECRET_KEY."));
		}
		long timestamp = Instant.now().toEpochMilli();
		String payload = String.format(
				"symbol=%s&side=%s&type=MARKET&quantity=%s&recvWindow=%d&timestamp=%d",
				symbol,
				side,
				quantity.toPlainString(),
				properties.recvWindowMillis(),
				timestamp);
		if (positionSide != null && !positionSide.isBlank()) {
			payload = payload + "&positionSide=" + positionSide;
		}
		String signature = signatureUtil.sign(payload, properties.secretKey());
		String signedPayload = payload + "&signature=" + signature;

		return binanceWebClient
				.post()
				.uri(uriBuilder -> uriBuilder
						.path("/fapi/v1/order")
						.query(signedPayload)
						.build())
				.header(HttpHeaders.CONTENT_TYPE, "application/x-www-form-urlencoded")
				.header("X-MBX-APIKEY", properties.apiKey())
				.retrieve()
				.onStatus(status -> status.isError(), response -> response
						.bodyToMono(String.class)
						.defaultIfEmpty("<empty>")
						.flatMap(body -> Mono.error(new IllegalStateException(
								"Binance order failed with status=" + response.statusCode().value()
										+ ", body=" + body))))
				.bodyToMono(OrderResponse.class);
	}

	public Mono<Boolean> fetchHedgeModeEnabled() {
		if (properties.apiKey() == null || properties.apiKey().isBlank()
				|| properties.secretKey() == null || properties.secretKey().isBlank()) {
			return Mono.error(new IllegalStateException(
					"Binance API key/secret is not configured. Set BINANCE_API_KEY and BINANCE_SECRET_KEY."));
		}
		long timestamp = Instant.now().toEpochMilli();
		String payload = String.format("recvWindow=%d&timestamp=%d", properties.recvWindowMillis(), timestamp);
		String signature = signatureUtil.sign(payload, properties.secretKey());
		String signedPayload = payload + "&signature=" + signature;

		return binanceWebClient
				.get()
				.uri(uriBuilder -> uriBuilder
						.path("/fapi/v1/positionSide/dual")
						.query(signedPayload)
						.build())
				.header(HttpHeaders.CONTENT_TYPE, "application/x-www-form-urlencoded")
				.header("X-MBX-APIKEY", properties.apiKey())
				.retrieve()
				.onStatus(status -> status.isError(), response -> response
						.bodyToMono(String.class)
						.defaultIfEmpty("<empty>")
						.flatMap(body -> Mono.error(new IllegalStateException(
								"Binance position mode fetch failed with status=" + response.statusCode().value()
										+ ", body=" + body))))
				.bodyToMono(PositionModeResponse.class)
				.map(PositionModeResponse::dualSidePosition);
	}

	private record PositionModeResponse(boolean dualSidePosition) {}
}
