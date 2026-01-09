package com.binance.strategy;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.binance.market.BinanceMarketClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

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
	private final KlineStreamWatcher klineStreamWatcher;
	private final MarkPriceStreamWatcher markPriceStreamWatcher;
	private final ObjectMapper objectMapper;
	private final SymbolFilterService symbolFilterService;

	public HistoricalWarmupService(BinanceMarketClient marketClient,
			StrategyRouter strategyRouter,
			StrategyProperties strategyProperties,
			WarmupProperties warmupProperties,
			CtiLbStrategy ctiLbStrategy,
			KlineStreamWatcher klineStreamWatcher,
			MarkPriceStreamWatcher markPriceStreamWatcher,
			ObjectMapper objectMapper,
			SymbolFilterService symbolFilterService) {
		this.marketClient = marketClient;
		this.strategyRouter = strategyRouter;
		this.strategyProperties = strategyProperties;
		this.warmupProperties = warmupProperties;
		this.ctiLbStrategy = ctiLbStrategy;
		this.klineStreamWatcher = klineStreamWatcher;
		this.markPriceStreamWatcher = markPriceStreamWatcher;
		this.objectMapper = objectMapper;
		this.symbolFilterService = symbolFilterService;
	}

	@PostConstruct
	public void start() {
		if (!warmupProperties.enabled() || strategyProperties.active() != StrategyType.CTI_LB) {
			return;
		}
		List<String> symbols = strategyProperties.resolvedTradeSymbols();
		symbolFilterService.preloadFilters(symbols)
				.then(warmupAllSymbols(symbols))
				.subscribe();
	}

	public Mono<Void> warmupAllSymbols(List<String> symbols) {
		long start = System.currentTimeMillis();
		int concurrency = resolveConcurrency();
		LOGGER.info("EVENT=WARMUP_START symbolsCount={} concurrency={}", symbols.size(), concurrency);
		ctiLbStrategy.setWarmupMode(true);
		AtomicInteger readySymbols = new AtomicInteger();
		AtomicInteger failedSymbols = new AtomicInteger();
		return Flux.fromIterable(symbols)
				.flatMap(symbol -> warmupSymbol(symbol)
						.doOnNext(ready -> {
							if (ready) {
								readySymbols.incrementAndGet();
							}
						})
						.onErrorResume(error -> {
							failedSymbols.incrementAndGet();
							LOGGER.warn("EVENT=WARMUP_SYMBOL symbol={} error={}", symbol, error.getMessage());
							scheduleRetry(symbol);
							return Mono.just(false);
						}), concurrency)
				.then()
				.then(Flux.fromIterable(symbols)
						.flatMap(ctiLbStrategy::refreshAfterWarmup, concurrency)
						.then())
				.doFinally(signal -> {
					boolean filtersReady = symbolFilterService.areFiltersReady(symbols);
					klineStreamWatcher.markWarmupComplete();
					klineStreamWatcher.startStreams();
					markPriceStreamWatcher.markWarmupComplete();
					markPriceStreamWatcher.startStreams();
					ctiLbStrategy.setWarmupMode(false);
					if (filtersReady) {
						ctiLbStrategy.enableOrdersAfterWarmup();
					}
					long durationMs = System.currentTimeMillis() - start;
					LOGGER.info("EVENT=WARMUP_DONE totalDurationMs={} readySymbols={} failedSymbols={}", durationMs,
							readySymbols.get(),
							failedSymbols.get());
				});
	}

	public Mono<Boolean> warmupSymbol(String symbol) {
		long start = System.currentTimeMillis();
		return warmupSymbolInterval(symbol, "5m", resolveCandles5m())
				.flatMap(count5m -> warmupSymbolInterval(symbol, "1m", resolveCandles1m())
						.map(count1m -> new WarmupCounts(count1m, count5m)))
				.map(counts -> {
					ScoreSignalIndicator.WarmupStatus status = strategyRouter.warmupStatus(symbol);
					boolean ready = status != null && status.cti5mReady() && status.adx5mReady();
					long durationMs = System.currentTimeMillis() - start;
					LOGGER.info("EVENT=WARMUP_DONE symbol={} candles1m={} candles5m={} cti5mBarsSeen={} adx5mBarsSeen={} ready={} durationMs={}",
							symbol,
							counts.candles1m(),
							counts.candles5m(),
							status == null ? 0 : status.cti5mBarsSeen(),
							status == null ? 0 : status.adx5mBarsSeen(),
							ready,
							durationMs);
					strategyRouter.markWarmupFinished(symbol, System.currentTimeMillis());
					return ready;
				});
	}

	private Mono<Integer> warmupSymbolInterval(String symbol, String interval, int limit) {
		return marketClient.fetchFuturesKlinesRaw(symbol, interval, limit)
				.map(response -> {
					return parseKlines(response.body(), symbol);
				})
				.onErrorMap(error -> new IllegalStateException("Warmup fetch failed for " + symbol + " " + interval,
						error))
				.doOnNext(klines -> {
					List<WarmupCandle> sorted = klines.stream()
							.sorted(Comparator.comparingLong(WarmupCandle::closeTime))
							.toList();
					for (WarmupCandle kline : sorted) {
						Candle candle = new Candle(
								kline.open().doubleValue(),
								kline.high().doubleValue(),
								kline.low().doubleValue(),
								kline.close().doubleValue(),
								kline.volume().doubleValue(),
								kline.closeTime());
						if ("5m".equals(interval)) {
							strategyRouter.warmupFiveMinuteCandle(symbol, candle);
						} else {
							strategyRouter.onClosedOneMinuteCandle(symbol, candle);
						}
					}
				})
				.map(List::size);
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

	private List<WarmupCandle> parseKlines(String json, String symbol) {
		try {
			JsonNode root = objectMapper.readTree(json);
			if (root == null || !root.isArray()) {
				throw new IllegalStateException("Unexpected kline payload for " + symbol);
			}
			java.util.ArrayList<WarmupCandle> candles = new java.util.ArrayList<>();
			for (JsonNode entry : root) {
				if (!entry.isArray() || entry.size() < 7) {
					continue;
				}
				long openTime = entry.get(0).asLong();
				BigDecimal open = new BigDecimal(entry.get(1).asText());
				BigDecimal high = new BigDecimal(entry.get(2).asText());
				BigDecimal low = new BigDecimal(entry.get(3).asText());
				BigDecimal close = new BigDecimal(entry.get(4).asText());
				BigDecimal volume = new BigDecimal(entry.get(5).asText());
				long closeTime = entry.get(6).asLong();
				candles.add(new WarmupCandle(openTime, closeTime, open, high, low, close, volume));
			}
			return candles;
		} catch (Exception ex) {
			throw new IllegalStateException("Failed to parse klines for " + symbol + ": " + ex.getMessage(), ex);
		}
	}

	private record WarmupCounts(int candles1m, int candles5m) {
	}

	private record WarmupCandle(
			long openTime,
			long closeTime,
			BigDecimal open,
			BigDecimal high,
			BigDecimal low,
			BigDecimal close,
			BigDecimal volume) {
	}
}
