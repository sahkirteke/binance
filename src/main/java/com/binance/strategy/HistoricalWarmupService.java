package com.binance.strategy;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
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
	private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.BASIC_ISO_DATE;

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
		List<String> symbols = strategyProperties.resolvedTradeSymbols();
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

	public Mono<Void> warmupAllSymbols(List<String> symbols) {
		long start = System.currentTimeMillis();
		int concurrency = resolveConcurrency();
		strategyRouter.setWarmupMode(true);
		LOGGER.info("EVENT=WARMUP_START required5mBars={} globalHistWindow={} symbolHistWindow={} minGlobalSamples={} minSymbolSamples={}",
				resolveCandles5m(),
				288,
				288,
				120,
				120);
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
			double readyRatio = total == 0 ? 0.0 : (double) ready / (double) total;
			double minReadyRatio = eliteV1Properties.selfCheck() == null ? 0.70 : eliteV1Properties.selfCheck().minReadyRatio();
			logGlobalGateStatus();
			LOGGER.info("EVENT=WARMUP_DONE_SUMMARY totalSymbols={} readySymbols={} disabledSymbols={} readyRatio={} durationMs={}",
					total,
					ready,
					notReady,
					readyRatio,
					durationMs);
			if (readyRatio < minReadyRatio) {
				LOGGER.warn("EVENT=WARMUP_FAILED readyRatio={} minReadyRatio={} topReasons={}",
						readyRatio,
						minReadyRatio,
						topReasons(notReadyReasons));
			}
			if (ready == total) {
				boolean filtersReady = symbolFilterService.areFiltersReady(symbols);
				klineStreamWatcher.markWarmupComplete();
				klineStreamWatcher.startStreams();
				markPriceStreamWatcher.markWarmupComplete();
				markPriceStreamWatcher.startStreams();
				strategyRouter.setWarmupMode(false);
				strategyRouter.enableOrdersAfterWarmup(filtersReady);
				LOGGER.info("EVENT=TRADING_READY symbolsReadyRatio={} globalSamples={} timeTr={} warmupModeEnabled={} warmupCompleted={} ordersAllowed={}",
						readyRatio,
						strategyRouter.eliteGlobalGateStatus() == null ? 0 : strategyRouter.eliteGlobalGateStatus().globalSamples(),
						java.time.ZonedDateTime.now(ZoneId.of("Europe/Istanbul")),
						false,
						true,
						filtersReady);
			} else {
				LOGGER.warn("EVENT=TRADING_NOT_READY blockReason=WARMUP_NOT_SUFFICIENT readySymbols={} totalSymbols={}", ready, total);
			}
			LOGGER.info("EVENT=WARMUP_DONE readySymbols={} notReadySymbols={} failedSymbols={} totalDurationMs={}",
					ready,
					notReady,
					failed,
					durationMs);
			notReadyReasons.forEach((symbol, reason) -> LOGGER.info("EVENT=WARMUP_NOT_READY symbol={} reason={}", symbol, reason));
			runSelfCheckIfEnabled(symbols);
		});
	}


	public Mono<WarmupReport> warmupSymbol(String symbol) {
		if (strategyProperties.active() == StrategyType.ELITE_V1) {
			return warmupSymbolInterval(symbol, "5m", resolveCandles5m())
					.map(count5m -> {
						var summary = strategyRouter.eliteSymbolWarmupSummary(symbol);
						String reason = summary == null ? "STATUS_NULL" : summary.reasonIfNotReady();
						WarmupReport report = summary == null
								? new WarmupReport(symbol, false, "STATUS_NULL")
								: new WarmupReport(symbol, summary.ready(), reason);
						LOGGER.info("EVENT=WARMUP_SYMBOL_SUMMARY symbol={} fetched={} applied={} have5m={} required={} baselinesSeeded={} metricsOk={} regimeOk={} symbolSamples={} reasonIfNotReady={}",
								symbol,
								count5m,
								count5m,
								summary == null ? 0 : summary.have5m(),
								summary == null ? resolveCandles5m() : summary.required5m(),
								summary != null && summary.baselinesSeeded(),
								summary != null && summary.metricsOk(),
								summary != null && summary.regimeOk(),
								summary == null ? 0 : summary.symbolSamples(),
								reason == null || reason.isBlank() ? "READY" : reason);
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
						} else if (strategyProperties.active() == StrategyType.CTI_LB) {
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

	private void runSelfCheckIfEnabled(List<String> symbols) {
		if (strategyProperties.active() != StrategyType.ELITE_V1 || eliteV1Properties.selfCheck() == null || !eliteV1Properties.selfCheck().enabled()) {
			return;
		}
		try {
			int durationSec = Math.max(10, eliteV1Properties.selfCheck().durationSec());
			Thread.sleep(durationSec * 1000L);
			Path decisionsDir = Paths.get("signals", "decisions");
			LocalDate today = LocalDate.now(ZoneId.of("Europe/Istanbul"));
			Path sampleFile = null;
			List<String> lines = new ArrayList<>();
			for (String symbol : symbols) {
				Path file = decisionsDir.resolve(symbol).resolve(today.format(DAY_FMT) + ".jsonl");
				if (!Files.exists(file)) {
					continue;
				}
				List<String> all = Files.readAllLines(file);
				int from = Math.max(0, all.size() - 50);
				lines.addAll(all.subList(from, all.size()));
				sampleFile = file;
			}
			SelfCheckResult result = evaluateSelfCheckLines(lines);
			LOGGER.info("EVENT=SELF_CHECK_RESULT ok={} failCount={} topFailures={} sampleFile={}",
					result.ok,
					result.failCount,
					result.topFailures,
					sampleFile == null ? "NONE" : sampleFile);
		} catch (Exception e) {
			LOGGER.warn("EVENT=SELF_CHECK_RESULT ok=false failCount=1 topFailures=[SELF_CHECK_EXCEPTION:{}] sampleFile=NONE", e.getMessage());
		}
	}

	private SelfCheckResult evaluateSelfCheckLines(List<String> lines) {
		Map<String, Integer> failures = new HashMap<>();
		int total = 0;
		int warmupNotReady = 0;
		for (String line : lines) {
			try {
				JsonNode n = objectMapper.readTree(line);
				total++;
				String blockReason = n.path("blockReason").asText("");
				if ("INPUTS_NOT_READY".equals(blockReason) || blockReason.contains("WARMUP")) {
					warmupNotReady++;
				}
				if (n.has("globalMedBw") && (n.path("globalMedBw").isNull() || n.path("bwThr").isNull() || n.path("globalChopShare").isNull() || n.path("chopThr").isNull())) {
					failures.merge("GLOBAL_FIELDS_NULL", 1, Integer::sum);
				}
				if (!n.path("inputsValid").asBoolean(true) && (!n.has("inputsInvalidReasons") || n.path("inputsInvalidReasons").isEmpty())) {
					failures.merge("INPUTS_INVALID_REASONS_EMPTY", 1, Integer::sum);
				}
				if (blockReason.startsWith("GLOBAL_WORST_REGIME_VETO") && (!blockReason.contains("globalMedBw") || !blockReason.contains("globalChopShare"))) {
					failures.merge("GLOBAL_VETO_DETAILS_MISSING", 1, Integer::sum);
				}
				if (blockReason.startsWith("CHASING_OVERBOUGHT_VETO") && (!blockReason.contains("emaDist") || !blockReason.contains("rsi") || !blockReason.contains("pb"))) {
					failures.merge("ANTI_CHASE_DETAILS_MISSING", 1, Integer::sum);
				}
			} catch (Exception ex) {
				failures.merge("JSON_PARSE_ERROR", 1, Integer::sum);
			}
		}
		if (total > 0 && ((double) warmupNotReady / (double) total) > 0.05) {
			failures.merge("WARMUP_NOT_READY_RATIO_HIGH", 1, Integer::sum);
		}
		List<String> top = failures.entrySet().stream()
				.sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
				.limit(3)
				.map(e -> e.getKey() + ":" + e.getValue())
				.toList();
		return new SelfCheckResult(failures.isEmpty(), failures.values().stream().mapToInt(Integer::intValue).sum(), top);
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

	private void logGlobalGateStatus() {
		if (strategyProperties.active() != StrategyType.ELITE_V1) {
			return;
		}
		var status = strategyRouter.eliteGlobalGateStatus();
		if (status == null) {
			LOGGER.warn("EVENT=GLOBAL_GATE_NOT_READY reason=STATUS_NULL");
			return;
		}
		LOGGER.info("EVENT=GLOBAL_GATE_STATUS globalSamples={} needed={} includedSymbolsForLastSnapshot={} minSymbolsForGlobal={} globalMedBw={} bwThr={} globalChopShare={} chopThr={} ready={} reasonIfNotReady={}",
				status.globalSamples(),
				status.needed(),
				status.includedSymbolsForLastSnapshot(),
				status.minSymbolsForGlobal(),
				status.globalMedBw(),
				status.bwThr(),
				status.globalChopShare(),
				status.chopThr(),
				status.ready(),
				status.reasonIfNotReady());
		if (!status.ready()) {
			LOGGER.warn("EVENT=GLOBAL_GATE_NOT_READY reason={} included={} need={} globalSamples={}",
					status.reasonIfNotReady(),
					status.includedSymbolsForLastSnapshot(),
					status.minSymbolsForGlobal(),
					status.globalSamples());
		}
	}

	private String topReasons(Map<String, String> reasonsBySymbol) {
		Map<String, Integer> counts = new HashMap<>();
		for (String reason : reasonsBySymbol.values()) {
			if (reason == null || reason.isBlank()) {
				continue;
			}
			for (String piece : reason.split("\\|")) {
				if (piece == null || piece.isBlank()) {
					continue;
				}
				counts.merge(piece, 1, Integer::sum);
			}
		}
		return counts.entrySet().stream()
				.sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
				.limit(3)
				.map(e -> e.getKey() + ":" + e.getValue())
				.toList()
				.toString();
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

	private record SelfCheckResult(boolean ok, int failCount, List<String> topFailures) {
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
