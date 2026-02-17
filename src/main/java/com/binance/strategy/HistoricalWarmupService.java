package com.binance.strategy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.binance.exchange.strategy.elite.EliteV1Properties;
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
	private static final int WARMUP_FETCH_BUFFER_5M = 5;
	private static final int WARMUP_FETCH_RETRY_5M = 5;
	private static final long FIVE_MIN_MS = 300_000L;
	private static final int MIN_GLOBAL_SAMPLES = 120;
	private static final double MIN_READY_RATIO = 0.70;

	private final BinanceMarketClient marketClient;
	private final StrategyRouter strategyRouter;
	private final StrategyProperties strategyProperties;
	private final WarmupProperties warmupProperties;
	private final KlineStreamWatcher klineStreamWatcher;
	private final MarkPriceStreamWatcher markPriceStreamWatcher;
	private final ObjectMapper objectMapper;
	private final SymbolFilterService symbolFilterService;
	private final EliteV1Properties eliteV1Properties;

	public HistoricalWarmupService(BinanceMarketClient marketClient,
			StrategyRouter strategyRouter,
			StrategyProperties strategyProperties,
			WarmupProperties warmupProperties,
			KlineStreamWatcher klineStreamWatcher,
			MarkPriceStreamWatcher markPriceStreamWatcher,
			ObjectMapper objectMapper,
			SymbolFilterService symbolFilterService,
			EliteV1Properties eliteV1Properties) {
		this.marketClient = marketClient;
		this.strategyRouter = strategyRouter;
		this.strategyProperties = strategyProperties;
		this.warmupProperties = warmupProperties;
		this.klineStreamWatcher = klineStreamWatcher;
		this.markPriceStreamWatcher = markPriceStreamWatcher;
		this.objectMapper = objectMapper;
		this.symbolFilterService = symbolFilterService;
		this.eliteV1Properties = eliteV1Properties;
	}

	@PostConstruct
	public void start() {
		if (!isWarmupEnabled() || !strategyRouter.needsKlines()) {
			return;
		}
		List<String> symbols = resolveWarmupSymbols();
		symbolFilterService.preloadFilters(symbols)
				.then(warmupAllSymbols(symbols))
				.subscribe();
	}

	private boolean isWarmupEnabled() {
		if (strategyProperties.active() == StrategyType.ELITE_V1) {
			return eliteV1Properties.warmup() != null && eliteV1Properties.warmup().enabled();
		}
		return warmupProperties.enabled();
	}

	private List<String> resolveWarmupSymbols() {
		List<String> source = strategyProperties.active() == StrategyType.ELITE_V1
				? eliteV1Properties.symbols()
				: strategyProperties.resolvedTradeSymbols();
		if (source == null) {
			return List.of();
		}
		List<String> sanitized = source.stream()
				.filter(java.util.Objects::nonNull)
				.map(String::trim)
				.filter(symbol -> !symbol.isBlank())
				.map(String::toUpperCase)
				.distinct()
				.filter(symbol -> strategyProperties.active() != StrategyType.ELITE_V1 || !symbol.contains("I"))
				.toList();
		if (strategyProperties.active() == StrategyType.ELITE_V1 && sanitized.size() != source.size()) {
			LOGGER.warn("EVENT=WARMUP_SYMBOLS_SANITIZED dropped={} reason=CONTAINS_I_OR_INVALID", source.size() - sanitized.size());
		}
		return sanitized;
	}

	public Mono<Void> warmupAllSymbols(List<String> symbols) {
		long start = System.currentTimeMillis();
		strategyRouter.setWarmupMode(true);
		if (strategyProperties.active() == StrategyType.ELITE_V1) {
			return warmupElite(symbols, start);
		}
		return warmupDefault(symbols, start);
	}

	private Mono<Void> warmupElite(List<String> symbols, long startMs) {
		int required = resolveCandles5m();
		long endMs = closedFiveMinuteEndMs(System.currentTimeMillis());
		LOGGER.info("EVENT=WARMUP_START required5m={} symbols={} endMs={}", required, symbols.size(), endMs);
		Map<String, String> notReadyReasons = new java.util.concurrent.ConcurrentHashMap<>();
		AtomicInteger disabled = new AtomicInteger();

		return Flux.fromIterable(symbols)
				.flatMap(symbol -> fetchExactClosedFiveMinuteCandles(symbol, required, endMs)
						.map(candles -> {
							if (candles.size() < required) {
								disabled.incrementAndGet();
								String reason = "INSUFFICIENT_HISTORY have=" + candles.size() + " required=" + required;
								strategyRouter.disableEliteSymbol(symbol, reason);
								notReadyReasons.put(symbol, reason);
								LOGGER.warn("EVENT=WARMUP_SYMBOL_FAILED symbol={} have={} required={} reason=INSUFFICIENT_HISTORY", symbol, candles.size(), required);
							}
							return Map.entry(symbol, candles);
						})
						.onErrorResume(error -> {
							disabled.incrementAndGet();
							String reason = "EXCEPTION " + error.getMessage();
							strategyRouter.disableEliteSymbol(symbol, reason);
							notReadyReasons.put(symbol, reason);
							LOGGER.warn("EVENT=WARMUP_SYMBOL_FAILED symbol={} have=0 required={} reason={}", symbol, required, error.getMessage());
							return Mono.just(Map.entry(symbol, List.<WarmupCandle>of()));
						}), resolveConcurrency())
				.collectMap(Map.Entry::getKey, Map.Entry::getValue)
				.flatMap(symbolCandles -> {
					applyEliteWarmupTimeMajor(symbols, symbolCandles);
					int ready = 0;
					for (String symbol : symbols) {
						var readiness = strategyRouter.eliteWarmupReadiness(symbol);
						if (readiness != null && readiness.ready()) {
							ready++;
						} else {
							String reason = readiness == null ? "STATUS_NULL" : readiness.reason();
							notReadyReasons.put(symbol, reason);
							long have = readiness == null ? 0 : readiness.have5m();
							long req = readiness == null ? required : readiness.required5m();
							LOGGER.info("EVENT=WARMUP_NOT_READY symbol={} reason={} have={}/{}", symbol, reason, have, req);
						}
						strategyRouter.markWarmupFinished(symbol, System.currentTimeMillis());
					}

					int total = symbols.size();
					int effectiveTotal = Math.max(1, total - disabled.get());
					double readyRatio = ready / (double) effectiveTotal;
					int globalSamples = strategyRouter.eliteGlobalSamples();
					boolean filtersReady = symbolFilterService.areFiltersReady(symbols);
					boolean warmupReady = readyRatio >= MIN_READY_RATIO && globalSamples >= MIN_GLOBAL_SAMPLES && filtersReady;

					strategyRouter.setWarmupMode(false);
					strategyRouter.enableOrdersAfterWarmup(true);
					klineStreamWatcher.markWarmupComplete();
					klineStreamWatcher.startStreams();
					markPriceStreamWatcher.markWarmupComplete();
					markPriceStreamWatcher.startStreams();

					long durationMs = System.currentTimeMillis() - startMs;
					LOGGER.info("EVENT=WARMUP_DONE readySymbols={} notReadySymbols={} failedSymbols={} totalDurationMs={}",
							ready,
							Math.max(0, total - ready),
							disabled.get(),
							durationMs);
					LOGGER.info("EVENT=WARMUP_DONE_SUMMARY readySymbols={} disabledSymbols={} totalSymbols={} readyRatio={} globalSamples={} warmupReady={}",
							ready,
							disabled.get(),
							total,
							readyRatio,
							globalSamples,
							warmupReady);
					notReadyReasons.forEach((symbol, reason) -> LOGGER.info("EVENT=WARMUP_NOT_READY symbol={} reason={}", symbol, reason));
					return Mono.empty();
				});
	}

	private void applyEliteWarmupTimeMajor(List<String> symbols, Map<String, List<WarmupCandle>> symbolCandles) {
		TreeMap<Long, Map<String, WarmupCandle>> byClose = new TreeMap<>();
		for (Map.Entry<String, List<WarmupCandle>> entry : symbolCandles.entrySet()) {
			for (WarmupCandle candle : entry.getValue()) {
				byClose.computeIfAbsent(candle.closeTime(), ignored -> new HashMap<>()).put(entry.getKey(), candle);
			}
		}
		for (Map.Entry<Long, Map<String, WarmupCandle>> timeEntry : byClose.entrySet()) {
			for (String symbol : symbols) {
				WarmupCandle kline = timeEntry.getValue().get(symbol);
				if (kline == null) {
					continue;
				}
				Candle candle = new Candle(
						kline.open().doubleValue(),
						kline.high().doubleValue(),
						kline.low().doubleValue(),
						kline.close().doubleValue(),
						kline.volume().doubleValue(),
						kline.closeTime());
				strategyRouter.warmupFiveMinuteCandle(symbol, candle);
			}
		}
	}

	private Mono<List<WarmupCandle>> fetchExactClosedFiveMinuteCandles(String symbol, int target, long baseEndMs) {
		int requestLimit = target + WARMUP_FETCH_BUFFER_5M;
		return fetchWithRetry(symbol, target, requestLimit, baseEndMs, 0, new TreeMap<>());
	}

	private Mono<List<WarmupCandle>> fetchWithRetry(String symbol,
			int target,
			int requestLimit,
			long endTimeMs,
			int attempt,
			TreeMap<Long, WarmupCandle> acc) {
		long startTimeMs = endTimeMs - (requestLimit * FIVE_MIN_MS) + 1;
		return marketClient.fetchFuturesKlinesRaw(symbol, "5m", startTimeMs, endTimeMs, requestLimit)
				.map(response -> parseKlines(response.body(), symbol))
				.flatMap(klines -> {
					List<WarmupCandle> filtered = klines.stream()
							.filter(k -> k.openTime() > 0L && k.closeTime() > 0L)
							.filter(k -> k.closeTime() <= endTimeMs)
							.sorted(Comparator.comparingLong(WarmupCandle::openTime))
							.toList();
					for (WarmupCandle candle : filtered) {
						acc.put(candle.openTime(), candle);
					}
					if (acc.size() >= target || attempt >= WARMUP_FETCH_RETRY_5M || filtered.isEmpty()) {
						List<WarmupCandle> out = new ArrayList<>(acc.values());
						out.sort(Comparator.comparingLong(WarmupCandle::openTime));
						if (out.size() > target) {
							out = out.subList(out.size() - target, out.size());
						}
						return Mono.just(out);
					}
					long firstOpen = filtered.get(0).openTime();
					long endTimeMs2 = firstOpen - 1;
					return fetchWithRetry(symbol, target, requestLimit, endTimeMs2, attempt + 1, acc);
				})
				.onErrorMap(error -> new IllegalStateException("Warmup fetch failed for " + symbol + " 5m", error));
	}

	private Mono<Void> warmupDefault(List<String> symbols, long startMs) {
		int concurrency = resolveConcurrency();
		AtomicInteger readySymbols = new AtomicInteger();
		AtomicInteger failedSymbols = new AtomicInteger();
		Map<String, String> notReadyReasons = new java.util.concurrent.ConcurrentHashMap<>();

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
					.flatMap(strategyRouter::refreshAfterWarmup, concurrency)
					.then());
		}

		return warmupFlow.doFinally(signal -> {
			int total = symbols.size();
			int ready = readySymbols.get();
			int failed = failedSymbols.get();
			int notReady = Math.max(0, total - ready);
			long durationMs = System.currentTimeMillis() - startMs;
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
		if (strategyProperties.active() == StrategyType.ELITE_V1) {
			return warmupSymbolInterval(symbol, "5m", resolveCandles5m())
					.map(count5m -> {
						var readiness = strategyRouter.eliteWarmupReadiness(symbol);
						WarmupReport report = readiness == null
								? new WarmupReport(symbol, false, "STATUS_NULL")
								: new WarmupReport(symbol, readiness.ready(), readiness.reason());
						if (report.ready()) {
							LOGGER.info("EVENT=WARMUP_READY symbol={} have5m={} required5m={}", symbol, count5m, resolveCandles5m());
						}
						strategyRouter.markWarmupFinished(symbol, System.currentTimeMillis());
						return report;
					});
		}
		return warmupSymbolInterval(symbol, "5m", resolveCandles5m())
				.flatMap(count5m -> warmupSymbolInterval(symbol, "1m", resolveCandles1m())
						.map(count1m -> new WarmupCounts(count1m, count5m)))
				.doOnNext(counts -> strategyRouter.flushWarmup(symbol))
				.map(counts -> {
					ScoreSignalIndicator.WarmupStatus status = strategyRouter.warmupStatus(symbol);
					boolean ready = status != null && status.cti5mReady() && status.adx5mReady();
					WarmupReport report = new WarmupReport(symbol, ready, ready ? "READY" : (status == null ? "STATUS_NULL" : "BASELINE_NOT_SEEDED"));
					strategyRouter.markWarmupFinished(symbol, System.currentTimeMillis());
					return report;
				});
	}

	private Mono<Integer> warmupSymbolInterval(String symbol, String interval, int limit) {
		return marketClient.fetchFuturesKlinesRaw(symbol, interval, limit)
				.map(response -> parseKlines(response.body(), symbol))
				.onErrorMap(error -> new IllegalStateException("Warmup fetch failed for " + symbol + " " + interval, error))
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
						} else if (strategyProperties.active() == StrategyType.CTI_LB) {
							strategyRouter.warmupOneMinuteCandle(symbol, candle);
						}
					}
				})
				.map(List::size);
	}

	private int resolveCandles1m() {
		return warmupProperties.candles1m() > 0 ? warmupProperties.candles1m() : DEFAULT_CANDLES_1M;
	}

	private int resolveCandles5m() {
		if (strategyProperties.active() == StrategyType.ELITE_V1 && eliteV1Properties.warmup() != null && eliteV1Properties.warmup().enabled()) {
			return eliteV1Properties.warmup().candles5m();
		}
		return warmupProperties.candles5m() > 0 ? warmupProperties.candles5m() : DEFAULT_CANDLES_5M;
	}

	private int resolveConcurrency() {
		return warmupProperties.concurrency() > 0 ? warmupProperties.concurrency() : DEFAULT_CONCURRENCY;
	}

	private long closedFiveMinuteEndMs(long nowMs) {
		return (nowMs / FIVE_MIN_MS) * FIVE_MIN_MS - 1L;
	}

	private List<WarmupCandle> parseKlines(String json, String symbol) {
		try {
			JsonNode root = objectMapper.readTree(json);
			if (root == null || !root.isArray()) {
				throw new IllegalStateException("Unexpected kline payload for " + symbol);
			}
			ArrayList<WarmupCandle> candles = new ArrayList<>();
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
