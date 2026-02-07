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
	private final KlineStreamWatcher klineStreamWatcher;
	private final MarkPriceStreamWatcher markPriceStreamWatcher;
	private final ObjectMapper objectMapper;
	private final SymbolFilterService symbolFilterService;

	public HistoricalWarmupService(BinanceMarketClient marketClient,
			StrategyRouter strategyRouter,
			StrategyProperties strategyProperties,
			WarmupProperties warmupProperties,
			KlineStreamWatcher klineStreamWatcher,
			MarkPriceStreamWatcher markPriceStreamWatcher,
			ObjectMapper objectMapper,
			SymbolFilterService symbolFilterService) {
		this.marketClient = marketClient;
		this.strategyRouter = strategyRouter;
		this.strategyProperties = strategyProperties;
		this.warmupProperties = warmupProperties;
		this.klineStreamWatcher = klineStreamWatcher;
		this.markPriceStreamWatcher = markPriceStreamWatcher;
		this.objectMapper = objectMapper;
		this.symbolFilterService = symbolFilterService;
	}

	@PostConstruct
	public void start() {
		if (!warmupProperties.enabled() || !strategyRouter.needsKlines()) {
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
		strategyRouter.setWarmupMode(true);
		AtomicInteger readySymbols = new AtomicInteger();
		AtomicInteger failedSymbols = new AtomicInteger();
		java.util.concurrent.ConcurrentHashMap<String, String> notReadyReasons = new java.util.concurrent.ConcurrentHashMap<>();

		Mono<Void> warmupFlow = Flux.fromIterable(symbols)
				.flatMap(symbol -> warmupSymbol(symbol)
						.doOnNext(report -> {
							if (report.ready()) {
								readySymbols.incrementAndGet();
							} else {
								notReadyReasons.put(symbol, report.reason());
							}
						})
						.onErrorResume(error -> {
							failedSymbols.incrementAndGet();
							notReadyReasons.put(symbol, "EXCEPTION " + error.getMessage());
							return Mono.just(WarmupReport.failed(symbol, error));
						}), concurrency)
				.then();

		if (strategyProperties.active() == StrategyType.CTI_LB) {
			warmupFlow = warmupFlow.then(Flux.fromIterable(symbols)
					.flatMap(symbol -> strategyRouter.refreshAfterWarmup(symbol), concurrency)
					.then());
		}

		return warmupFlow.doFinally(signal -> {
			int total = symbols.size();
			int ready = readySymbols.get();
			int failed = failedSymbols.get();
			int notReady = Math.max(0, total - ready);
			long durationMs = System.currentTimeMillis() - start;
			if (ready == total) {
				boolean filtersReady = symbolFilterService.areFiltersReady(symbols);
				klineStreamWatcher.markWarmupComplete();
				klineStreamWatcher.startStreams();
				markPriceStreamWatcher.markWarmupComplete();
				markPriceStreamWatcher.startStreams();
				strategyRouter.setWarmupMode(false);
				strategyRouter.enableOrdersAfterWarmup(filtersReady);
			}
			LOGGER.info("EVENT=WARMUP_DONE readySymbols={} notReadySymbols={} failedSymbols={} totalDurationMs={}",
					ready,
					notReady,
					failed,
					durationMs);
			notReadyReasons.forEach((symbol, reason) -> LOGGER.info("EVENT=WARMUP_NOT_READY symbol={} reason={}", symbol, reason));
		});
	}


	public Mono<WarmupReport> warmupSymbol(String symbol) {
		return warmupSymbolInterval(symbol, "5m", resolveCandles5m())
				.flatMap(count5m -> warmupSymbolInterval(symbol, "1m", resolveCandles1m())
						.map(count1m -> new WarmupCounts(count1m, count5m)))
				.map(counts -> {
					WarmupReport report;
					if (strategyProperties.active() == StrategyType.CTI_LB) {
						ScoreSignalIndicator.WarmupStatus status = strategyRouter.warmupStatus(symbol);
						boolean ready = status != null && status.cti5mReady() && status.adx5mReady();
						report = new WarmupReport(symbol, ready, ready ? "READY" : (status == null ? "STATUS_NULL" : "BASELINE_NOT_SEEDED"));
					} else {
						var readiness = strategyRouter.eliteWarmupReadiness(symbol);
						if (readiness == null) {
							report = new WarmupReport(symbol, false, "STATUS_NULL");
						} else {
							report = new WarmupReport(symbol, readiness.ready(), readiness.reason());
						}
					}
					strategyRouter.markWarmupFinished(symbol, System.currentTimeMillis());
					return report;
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
							strategyRouter.warmupOneMinuteCandle(symbol, candle);
						}
					}
				})
				.map(List::size);
	}

	private void scheduleRetry(String symbol) {
		Mono.delay(Duration.ofSeconds(30))
				.then(warmupSymbol(symbol).then())
				.onErrorResume(error -> Mono.empty())
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

	private record WarmupReport(String symbol, boolean ready, String reason) {
		static WarmupReport failed(String symbol, Throwable error) {
			return new WarmupReport(symbol, false, "EXCEPTION " + error.getMessage());
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
