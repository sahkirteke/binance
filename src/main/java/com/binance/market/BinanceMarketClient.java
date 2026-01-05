package com.binance.market;

import java.math.BigDecimal;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import com.binance.market.dto.OrderBookDepthResponse;
import com.binance.market.dto.BookTickerResponse;
import com.binance.market.dto.MarkPriceResponse;

import reactor.core.publisher.Mono;

@Component
public class BinanceMarketClient {

	private final WebClient binanceWebClient;
	private final WebClient spotWebClient;

	public BinanceMarketClient(WebClient binanceWebClient,
			@Qualifier("spotWebClient") WebClient spotWebClient) {
		this.binanceWebClient = binanceWebClient;
		this.spotWebClient = spotWebClient;
	}

	public Mono<BigDecimal> fetchMarkPrice(String symbol) {
		return binanceWebClient
				.get()
				.uri(uriBuilder -> uriBuilder
						.path("/fapi/v1/premiumIndex")
						.queryParam("symbol", symbol)
						.build())
				.retrieve()
				.bodyToMono(MarkPriceResponse.class)
				.map(MarkPriceResponse::markPrice);
	}

	public Mono<OrderBookDepthResponse> fetchOrderBookDepth(String symbol, int limit) {
		return binanceWebClient
				.get()
				.uri(uriBuilder -> uriBuilder
						.path("/fapi/v1/depth")
						.queryParam("symbol", symbol)
						.queryParam("limit", limit)
						.build())
				.retrieve()
				.bodyToMono(OrderBookDepthResponse.class);
	}

	public Mono<OrderBookDepthResponse> fetchSpotOrderBookDepth(String symbol, int limit) {
		return spotWebClient
				.get()
				.uri(uriBuilder -> uriBuilder
						.path("/api/v3/depth")
						.queryParam("symbol", symbol)
						.queryParam("limit", limit)
						.build())
				.retrieve()
				.bodyToMono(OrderBookDepthResponse.class);
	}

	public Mono<BookTickerResponse> fetchFuturesBookTicker(String symbol) {
		return binanceWebClient
				.get()
				.uri(uriBuilder -> uriBuilder
						.path("/fapi/v1/ticker/bookTicker")
						.queryParam("symbol", symbol)
						.build())
				.retrieve()
				.bodyToMono(BookTickerResponse.class);
	}

	public Mono<String> fetchFuturesKlinesRaw(String symbol, String interval, int limit) {
		return binanceWebClient
				.get()
				.uri(uriBuilder -> uriBuilder
						.path("/fapi/v1/klines")
						.queryParam("symbol", symbol)
						.queryParam("interval", interval)
						.queryParam("limit", limit)
						.build())
				.retrieve()
				.bodyToMono(String.class);
	}
}
