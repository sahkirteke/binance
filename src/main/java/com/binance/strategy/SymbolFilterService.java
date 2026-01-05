package com.binance.strategy;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.binance.exchange.BinanceFuturesOrderClient;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

@Component
public class SymbolFilterService {

	private static final Logger LOGGER = LoggerFactory.getLogger(SymbolFilterService.class);

	private final BinanceFuturesOrderClient orderClient;
	private final Map<String, BinanceFuturesOrderClient.SymbolFilters> cache = new ConcurrentHashMap<>();

	public SymbolFilterService(BinanceFuturesOrderClient orderClient) {
		this.orderClient = orderClient;
	}

	public Mono<Void> preloadFilters(List<String> symbols, int concurrency) {
		return Flux.fromIterable(symbols)
				.flatMap(this::refreshFilters, concurrency)
				.then();
	}

	public BinanceFuturesOrderClient.SymbolFilters getFilters(String symbol) {
		return cache.get(symbol);
	}

	public boolean areFiltersReady(List<String> symbols) {
		return symbols.stream().allMatch(symbol -> cache.containsKey(symbol));
	}

	public Mono<BinanceFuturesOrderClient.SymbolFilters> refreshFilters(String symbol) {
		return orderClient.fetchSymbolFilters(symbol)
				.doOnNext(filters -> {
					cache.put(symbol, filters);
					LOGGER.info("EVENT=FILTERS_READY symbol={} stepSize={} minQty={} minNotional={} tickSize={}",
							symbol,
							filters.stepSize(),
							filters.minQty(),
							filters.minNotional(),
							filters.tickSize());
				})
				.doOnError(error -> LOGGER.warn("EVENT=FILTERS_FAIL symbol={} error={}", symbol, error.getMessage()))
				.retryWhen(Retry.backoff(5, Duration.ofSeconds(1))
						.maxBackoff(Duration.ofSeconds(5)))
				.onErrorResume(error -> Mono.empty());
	}
}
