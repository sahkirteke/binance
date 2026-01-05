package com.binance.strategy;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.binance.market.BinanceMarketClient;
import com.binance.market.dto.FuturesKline;

import jakarta.annotation.PostConstruct;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Component
public class HistoricalWarmupService {

	private static final Logger LOGGER = LoggerFactory.getLogger(HistoricalWarmupService.class);
	private static final int DEFAULT_CANDLES_1M = 240;
	private static final int DEFAULT_CANDLES_5M = 120;
	private static final int DEFAULT_CONCURRENCY = 3;

	private final BinanceMarketClient marketClient;
	private final StrategyRouter strategyRouter;
	private final StrategyProperties strategyProperties;
	private final WarmupProperties warmupProperties;
	private final CtiLbStrategy ctiLbStrategy;

	public HistoricalWarmupService(BinanceMarketClient marketClient,
			StrategyRouter strategyRouter,
			StrategyProperties strategyProperties,
			WarmupProperties warmupProperties,
			CtiLbStrategy ctiLbStrategy) {
		this.marketClient = marketClient;
		this.strategyRouter = strategyRouter;
		this.strategyProperties = strategyProperties;
		this.warmupProperties = warmupProperties;
		this.ctiLbStrategy = ctiLbStrategy;
	}

	@PostConstruct
	public void start() {
		if (!warmupProperties.enabled() || strategyProperties.active() != StrategyType.CTI_LB) {
			return;
		}
		warmupAllSymbols(strategyProperties.resolvedTradeSymbols())
				.subscribe();
	}

	public Mono<Void> warmupAllSymbols(List<String> symbols) {
		long start = System.currentTimeMillis();
		int concurrency = resolveConcurrency();
		LOGGER.info("EVENT=WARMUP_START symbolsCount={} concurrency={}", symbols.size(), concurrency);
		ctiLbStrategy.setWarmupMode(true);
		AtomicInteger readySymbols = new AtomicInteger();
		return Flux.fromIterable(symbols)
				.flatMap(symbol -> warmupSymbol(symbol)
						.doOnNext(ready -> {
							if (ready) {
								readySymbols.incrementAndGet();
							}
						})
						.onErrorResume(error -> {
							LOGGER.warn("EVENT=WARMUP_SYMBOL symbol={} error={}", symbol, error.getMessage());
							scheduleRetry(symbol);
							return Mono.just(false);
						}), concurrency)
				.then()
				.doFinally(signal -> {
					ctiLbStrategy.setWarmupMode(false);
					long durationMs = System.currentTimeMillis() - start;
					LOGGER.info("EVENT=WARMUP_DONE totalDurationMs={} readySymbols={}", durationMs,
							readySymbols.get());
				});
	}

	public Mono<Boolean> warmupSymbol(String symbol) {
		return warmupSymbolInterval(symbol, "5m", resolveCandles5m())
				.then(warmupSymbolInterval(symbol, "1m", resolveCandles1m()))
				.thenReturn(strategyRouter.isWarmupReady(symbol));
	}

	private Mono<Void> warmupSymbolInterval(String symbol, String interval, int limit) {
		long start = System.currentTimeMillis();
		return marketClient.fetchFuturesKlines(symbol, interval, limit)
				.doOnNext(klines -> {
					List<FuturesKline> sorted = klines.stream()
							.sorted(Comparator.comparingLong(FuturesKline::closeTime))
							.toList();
					for (FuturesKline kline : sorted) {
						Candle candle = new Candle(kline.open(), kline.high(), kline.low(), kline.close(),
								kline.closeTime());
						if ("5m".equals(interval)) {
							strategyRouter.warmupFiveMinuteCandle(symbol, candle);
						} else {
							strategyRouter.onClosedCandle(symbol, candle);
						}
					}
					long durationMs = System.currentTimeMillis() - start;
					LOGGER.info("EVENT=WARMUP_SYMBOL symbol={} tf={} candles={} durationMs={}",
							symbol,
							interval,
							sorted.size(),
							durationMs);
				})
				.then();
	}

	private void scheduleRetry(String symbol) {
		Mono.delay(Duration.ofSeconds(30))
				.then(warmupSymbol(symbol))
				.onErrorResume(error -> {
					LOGGER.warn("EVENT=WARMUP_SYMBOL symbol={} retryError={}", symbol, error.getMessage());
					return Mono.empty();
				})
				.subscribe();
	}

	private int resolveCandles1m() {
		return warmupProperties.candles1m() > 0 ? warmupProperties.candles1m() : DEFAULT_CANDLES_1M;
	}

	private int resolveCandles5m() {
		return warmupProperties.candles5m() > 0 ? warmupProperties.candles5m() : DEFAULT_CANDLES_5M;
	}

	private int resolveConcurrency() {
		return warmupProperties.concurrency() > 0 ? warmupProperties.concurrency() : DEFAULT_CONCURRENCY;
	}
}
