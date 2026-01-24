package com.binance.strategy;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

import com.binance.exchange.BinanceFuturesOrderClient;

import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

@Component
public class SymbolFilterService {

	private static final Logger LOGGER = LoggerFactory.getLogger(SymbolFilterService.class);
	private static final Duration RETRY_MIN_BACKOFF = Duration.ofSeconds(2);
	private static final Duration RETRY_MAX_BACKOFF = Duration.ofSeconds(30);

	private final BinanceFuturesOrderClient orderClient;
	private final Map<String, BinanceFuturesOrderClient.SymbolFilters> cache = new ConcurrentHashMap<>();
	private final Set<String> trackedSymbols = ConcurrentHashMap.newKeySet();
	private final AtomicBoolean filtersReady = new AtomicBoolean(false);
	private final AtomicBoolean refreshInFlight = new AtomicBoolean(false);
	private final AtomicLong lastRefreshMs = new AtomicLong(0L);
	private final StrategyProperties strategyProperties;
	private final Duration refreshTtl;

	public SymbolFilterService(BinanceFuturesOrderClient orderClient, StrategyProperties strategyProperties) {
		this.orderClient = orderClient;
		this.strategyProperties = strategyProperties;
		long ttlMs = Math.max(0L, strategyProperties.filterRefreshTtlMs());
		this.refreshTtl = ttlMs == 0L ? Duration.ZERO : Duration.ofMillis(ttlMs);
	}

	@PostConstruct
	public void init() {
		if (strategyProperties.active() == StrategyType.CTI_LB) {
			preloadFilters(strategyProperties.resolvedTradeSymbols())
					.subscribe(null, error -> LOGGER.warn("EVENT=FILTERS_PRELOAD_SUBSCRIBE_FAIL reason={}", error.getMessage()));
		}
	}

	public Mono<Void> preloadFilters(List<String> symbols) {
		trackedSymbols.clear();
		trackedSymbols.addAll(symbols);
		return refreshAllFilters();
	}

	public BinanceFuturesOrderClient.SymbolFilters getFilters(String symbol) {
		return cache.get(normalizeSymbol(symbol));
	}

	public boolean areFiltersReady(List<String> symbols) {
		return symbols.stream().allMatch(symbol -> cache.containsKey(normalizeSymbol(symbol)));
	}

	public boolean filtersReady() {
		return filtersReady.get();
	}

	public void requestRefresh() {
		refreshAllFilters()
				.subscribe(null, error -> LOGGER.warn("EVENT=FILTERS_REFRESH_SUBSCRIBE_FAIL reason={}", error.getMessage()));
	}

	private Mono<Void> refreshAllFilters() {
		if (!refreshInFlight.compareAndSet(false, true)) {
			return Mono.empty();
		}
		if (!shouldRefresh()) {
			refreshInFlight.set(false);
			return Mono.empty();
		}
		return orderClient.fetchExchangeInfo()
				.map(response -> parseExchangeInfo(response, trackedSymbols))
				.doOnNext(parsed -> {
					cache.clear();
					cache.putAll(parsed);
					boolean readyNow = !trackedSymbols.isEmpty() && areFiltersReady(List.copyOf(trackedSymbols));
					filtersReady.set(readyNow);
					parsed.forEach((symbol, filters) -> LOGGER.info(
							"EVENT=FILTERS_READY_FOR_SYMBOL symbol={} stepSize={} minQty={} minNotional={} tickSize={}",
							symbol,
							filters.stepSize(),
							filters.minQty(),
							filters.minNotional(),
							filters.tickSize()));
					logMissingSymbols(parsed.keySet());
					lastRefreshMs.set(System.currentTimeMillis());
					LOGGER.info("EVENT=FILTERS_GLOBAL_OK symbolsReady={}", parsed.size());
				})
				.retryWhen(Retry.backoff(4, RETRY_MIN_BACKOFF)
						.maxBackoff(RETRY_MAX_BACKOFF)
						.jitter(0.2))
				.doOnError(error -> LOGGER.warn("EVENT=FILTERS_GLOBAL_FAIL reason={}", error.getMessage()))
				.doFinally(signal -> refreshInFlight.set(false))
				.then();
	}

	static Map<String, BinanceFuturesOrderClient.SymbolFilters> parseExchangeInfo(
			BinanceFuturesOrderClient.ExchangeInfoResponse response,
			Collection<String> symbols) {
		if (response == null || symbols == null || symbols.isEmpty()) {
			return new ConcurrentHashMap<>();
		}
		Set<String> targets = symbols.stream()
				.map(SymbolFilterService::normalizeSymbol)
				.filter(java.util.Objects::nonNull)
				.collect(java.util.stream.Collectors.toSet());
		Map<String, BinanceFuturesOrderClient.SymbolFilters> parsed = new ConcurrentHashMap<>();
		if (response.symbols() == null) {
			return parsed;
		}
		for (BinanceFuturesOrderClient.SymbolInfo info : response.symbols()) {
			if (info == null) {
				continue;
			}
			if (info.symbol() == null || !targets.contains(normalizeSymbol(info.symbol()))) {
				continue;
			}
			BinanceFuturesOrderClient.SymbolFilters filters = BinanceFuturesOrderClient.resolveSymbolFilters(info);
			parsed.put(normalizeSymbol(info.symbol()), filters);
		}
		return parsed;
	}

	private static String normalizeSymbol(String symbol) {
		if (symbol == null || symbol.isBlank()) {
			return null;
		}
		return symbol.toUpperCase();
	}

	private boolean shouldRefresh() {
		if (refreshTtl.isZero()) {
			return true;
		}
		long lastRefresh = lastRefreshMs.get();
		if (lastRefresh == 0L) {
			return true;
		}
		return System.currentTimeMillis() - lastRefresh >= refreshTtl.toMillis();
	}

	private void logMissingSymbols(Set<String> loadedSymbols) {
		Set<String> missing = trackedSymbols.stream()
				.map(SymbolFilterService::normalizeSymbol)
				.filter(symbol -> !loadedSymbols.contains(symbol))
				.collect(java.util.stream.Collectors.toSet());
		if (!missing.isEmpty()) {
			LOGGER.warn("EVENT=FILTERS_FAIL missingSymbols={}", String.join(",", missing));
			filtersReady.set(false);
		}
	}
}
