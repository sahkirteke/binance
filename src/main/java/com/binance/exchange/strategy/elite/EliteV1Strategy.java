package com.binance.exchange.strategy.elite;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.lang.reflect.Method;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import com.binance.strategy.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import com.binance.config.BinanceProperties;
import com.binance.exchange.BinanceFuturesOrderClient;
import com.binance.exchange.dto.OrderResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import jakarta.annotation.PreDestroy;

@Component
public class EliteV1Strategy implements Strategy {

	private static final Logger LOGGER = LoggerFactory.getLogger(EliteV1Strategy.class);
	private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.BASIC_ISO_DATE;
	private static final DateTimeFormatter ISO_OFFSET_FMT = DateTimeFormatter.ISO_OFFSET_DATE_TIME;
	private static final long ONE_MIN_MS = 60_000L;
	private static final long FIVE_MIN_MS = 300_000L;
	private static final double TP_PCT = 0.0080;
	private static final double SL_PCT = 0.0030;
	private static final int LOOKAHEAD_BARS = 24;
	private static final long LOOKAHEAD_MS = LOOKAHEAD_BARS * FIVE_MIN_MS;
	private static final double DEFAULT_TICK_SIZE = 0.01;
	private static final long LIQUIDITY_MAX_AGE_MS = 30_000L;
	private static final String BTC_SYMBOL = "BTCUSDT";


// Global cap to avoid spraying too many concurrent positions/brackets.
// One ENTRY typically creates a bracket (TP+SL), so capping OPEN POSITIONS acts as a practical cap on orders.
private static final int MAX_CONCURRENT_OPEN_POSITIONS = 10;

// Winrate boosters (derived from DECISION/TRADES analysis):
// - Avoid CHOP upper-band longs (top-buy)
// - Avoid volume spikes (panic entries)
// - Avoid EMA chase (too far above EMA20)
private static final double VOL_RATIO_OF_EMA_MAX = 0.83;
	private static final double SHORT_BW_RATIO_MAX = 1.1;
	private static final double SHORT_MACD_RATIO_MAX = 1.4;
	private static final double SHORT_ATR_RATIO_MAX = 1.1;
	private static final double SPREAD_PCT_MAX = 0.0012;
	private static final double LONG_FROM_HIGH_1H_MAX = -0.02261;
	private static final double LONG_EMA20_DIST_MAX = 0.00234;

	private static final Path DECISION_DIR = Paths.get("signals", "decisions");
	private static final Path TRADE_DIR = Paths.get("signals", "trades");

	private final BinanceProperties binanceProperties;
	private final EliteV1Properties props;
	private final ObjectMapper objectMapper;
	private final SymbolFilterService symbolFilterService;
	private final WarmupProperties warmupProperties;
	private final ApplicationContext applicationContext;
	private final BinanceFuturesOrderClient orderClient;
	private final Map<String, SymbolState> states = new ConcurrentHashMap<>();
	private final AtomicBoolean started = new AtomicBoolean(false);
	private final AsyncJsonlWriter writer = new AsyncJsonlWriter(20_000);
	private final AtomicInteger globalOpenPositions = new AtomicInteger(0);
	private ZoneId zoneId;
	private int requiredWarmup5m;
	private WarmupMode warmupMode = WarmupMode.DERIVE_5M_FROM_1M;
	private final AtomicBoolean warmupModeEnabled = new AtomicBoolean(false);
	private volatile boolean warmupCompleted;
	private final BookTickerStreamWatcher bookTickerStreamWatcher;


	public EliteV1Strategy(BinanceProperties binanceProperties,
                           EliteV1Properties props,
                           ObjectMapper objectMapper,
                           SymbolFilterService symbolFilterService,
                           WarmupProperties warmupProperties,
                           ApplicationContext applicationContext,
                           BinanceFuturesOrderClient orderClient, BookTickerStreamWatcher bookTickerStreamWatcher) {
		this.binanceProperties = binanceProperties;
		this.props = props;
		this.objectMapper = objectMapper;
		this.symbolFilterService = symbolFilterService;
		this.warmupProperties = warmupProperties;
		this.applicationContext = applicationContext;
		this.orderClient = orderClient;
        this.bookTickerStreamWatcher = bookTickerStreamWatcher;
    }

	@Override
	public StrategyType type() {
		return StrategyType.ELITE_V1;
	}

	@Override
	public void start() {
		ensureInitialized();
	}

	private void ensureInitialized() {
		if (!started.compareAndSet(false, true)) {
			return;
		}
		validateConfig();
		warmupCompleted = !isHistoricalWarmupEnabled();
		requiredWarmup5m = resolveRequiredWarmup5m(props, warmupProperties);
		LOGGER.info("EVENT=WARMUP_PLAN strategy=ELITE_V1 symbols={} warmup5m={} mode={}",
				props.symbols().size(),
				requiredWarmup5m,
				warmupMode.name());
		LOGGER.info("EVENT=WARMUP_GATE strategy=ELITE_V1 historicalWarmupEnabled={} warmupCompletedInitially={}",
				isHistoricalWarmupEnabled(),
				warmupCompleted);
		zoneId = ZoneId.of(props.zoneId());
		writer.start();
		props.symbols().forEach(symbol -> states.put(symbol, new SymbolState(symbol)));
		symbolFilterService.preloadFilters(props.symbols()).subscribe();
		startBookTickerWatcher();
		LOGGER.info("ELITE_V1 started mode={} symbols={} zone={} feed=EXTERNAL_KLINE_WATCHER", props.mode(), props.symbols().size(), props.zoneId());
	}

	private boolean isHistoricalWarmupEnabled() {
		return props.warmup() != null && props.warmup().enabled();
	}

	@Override
	public void stop() {
		if (!started.compareAndSet(true, false)) {
			return;
		}
		writer.stop();
	}

	@PreDestroy
	public void onDestroy() {
		stop();
	}

	public void onExternalClosedOneMinuteCandle(String symbol, Candle candle) {
		ensureInitialized();
		SymbolState state = resolveState(symbol);
		onClosed1m(state, candle);
	}

	public void flushWarmup(String symbol) {
		ensureInitialized();
		SymbolState state = resolveState(symbol);
		BucketTransition transition = state.aggregator.flush();
		applyCompletedFiveMinute(state, transition);
	}

	public void warmupFiveMinuteCandle(String symbol, Candle bar5m) {
		ensureInitialized();
		SymbolState state = resolveState(symbol);
		applyCompletedFiveMinute(state, new BucketTransition(bar5m, null, 0));
	}

	public void setWarmupMode(boolean warmupMode) {
		warmupModeEnabled.set(warmupMode);
		warmupCompleted = false;
	}

	public void enableOrdersAfterWarmup() {
		warmupModeEnabled.set(false);
		warmupCompleted = true;
	}

	public boolean isWarmupReady(String symbol) {
		SymbolState state = resolveState(symbol);
		return isBaselinesReady(state, state.indicators.metrics());
	}

	public WarmupReadiness warmupReadiness(String symbol) {
		SymbolState state = resolveState(symbol);
		Metrics metrics = state.indicators.metrics();
		boolean seeded = state.indicators.baselineIndicatorsSeeded();
		if (state.seen5mCloses < requiredWarmup5m) {
			return WarmupReadiness.notReady(symbol, state.seen1mCloses, 0, state.seen5mCloses, requiredWarmup5m,
					"INSUFFICIENT_5M_BARS " + state.seen5mCloses + "/" + requiredWarmup5m);
		}
		if (!seeded || metrics == null) {
			return WarmupReadiness.notReady(symbol, state.seen1mCloses, 0, state.seen5mCloses, requiredWarmup5m,
					"BASELINE_NOT_SEEDED");
		}
		return WarmupReadiness.ready(symbol, state.seen1mCloses, 0, state.seen5mCloses, requiredWarmup5m);
	}

	private SymbolState resolveState(String symbol) {
		return states.computeIfAbsent(symbol, SymbolState::new);
	}


	private void onClosed1m(SymbolState state, Candle bar1m) {
		state.seen1mCloses++;
		state.last1m.addLast(bar1m);
		if (state.last1m.size() > 300) {
			state.last1m.removeFirst();
		}
		logWarmupProgressIfDue(state);
		BucketTransition transition = state.aggregator.addFinalOneMinute(bar1m);
		applyCompletedFiveMinute(state, transition);
	}

	private void applyCompletedFiveMinute(SymbolState state, BucketTransition transition) {
		if (transition == null) {
			return;
		}
		if (transition.incompleteBucketStartMs != null) {
			LOGGER.debug("EVENT=INCOMPLETE_5M_BUCKET symbol={} bucketStartMs={} count={}",
					state.symbol,
					transition.incompleteBucketStartMs,
					transition.incompleteCount);
		}
		Candle bar5m = transition.completedCandle;
		if (bar5m == null) {
			return;
		}
		state.seen5mCloses++;
		state.last5m.addLast(bar5m);
		if (state.last5m.size() > 500) {
			state.last5m.removeFirst();
		}
		state.indicators.update(bar5m);
		Metrics metrics = state.indicators.metrics();
		if (metrics != null) {
			RegimeTag rawRegime = rawRegime(metrics.bwRatio5m, metrics.macdRatio5m,
					props.regime().chopBwRatioMax(), props.regime().chopMacdRatioMax());
			RegimeTag activeRegime = state.regimeState.update(rawRegime,
					props.regime().debounceBars(), props.regime().cooldownBars());
			state.indicators.setRegimes(rawRegime, activeRegime);
		}
		evaluateAt5m(state, bar5m);
	}

	private void logWarmupProgressIfDue(SymbolState state) {
		if (state.warmupDoneLogged || state.seen1mCloses < state.nextWarmupProgressLogAt1m) {
			return;
		}
		boolean seeded = state.indicators.baselineIndicatorsSeeded();
		LOGGER.info("EVENT=WARMUP_PROGRESS strategy=ELITE_V1 symbol={} seen1m={} seen5m={}/{} seeded={} baselinesReady={} nextLogAt1m={}",
				state.symbol,
				state.seen1mCloses,
				state.seen5mCloses,
				requiredWarmup5m,
				seeded,
				state.baselinesReady,
				state.nextWarmupProgressLogAt1m);
		state.nextWarmupProgressLogAt1m += state.nextWarmupProgressLogAt1m < 60 ? 10 : 60;
	}

	private void evaluateAt5m(SymbolState state, Candle bar5m) {
		long closeTimeMs = bar5m.closeTime();
		if (state.lastEvaluated5mCloseTimeMs == closeTimeMs) {
			LOGGER.debug("EVENT=DUPLICATE_5M_IGNORED strategy=ELITE_V1 symbol={} closeTimeMs={}", state.symbol, closeTimeMs);
			return;
		}
		state.lastEvaluated5mCloseTimeMs = closeTimeMs;
		rollDay(state, closeTimeMs);
		Metrics metrics = state.indicators.metrics();
		boolean baselinesReady = isBaselinesReady(state, metrics);
		state.baselinesReady = baselinesReady;
		if (baselinesReady && !state.warmupDoneLogged && metrics != null) {
			state.warmupDoneLogged = true;
			var at = Instant.ofEpochMilli(bar5m.closeTime());
			var atTr = at.atZone(zoneId);
			LOGGER.info("EVENT=WARMUP_DONE strategy=ELITE_V1 symbol={} seen1m={} seen5m={} required5m={} atMs={} timeUtc={} timeTr={}",
					state.symbol,
					state.seen1mCloses,
					state.seen5mCloses,
					requiredWarmup5m,
					bar5m.closeTime(),
					at.toString(),
					ISO_OFFSET_FMT.format(atTr));
		}
		if (state.positionSide != Side.NONE) {
			boolean exited = props.mode() == EliteV1Properties.Mode.PAPER
					? checkPaperExitOnFiveMinute(state, bar5m)
					: checkLiveBracketExit(state, bar5m);
			if (exited) {
				return;
			}
		}
		if (warmupModeEnabled.get() || !warmupCompleted || !baselinesReady || metrics == null) {
			return;
		}

		RegimeTag decisionRegimeTag = metrics.activeRegimeTag != null ? metrics.activeRegimeTag : RegimeTag.CHOP;
		LongSetupEval longEval = evaluateBaselineImpulseReclaim(state, metrics, bar5m);
		boolean longOpened = false;
		if (longEval.signal() && state.positionSide == Side.NONE) {
			longOpened = openPosition(state, bar5m.close(), bar5m.closeTime(), Side.LONG, "BASELINE_IMPULSE_RECLAIM", decisionRegimeTag);
		}

		ShortSetupEval shortEval = evaluateShortDumpBtcSetup(state, bar5m, metrics);
		boolean shortOpened = false;
		if (shortEval.pass() && state.positionSide == Side.NONE) {
			shortOpened = openPosition(state, bar5m.close(), bar5m.closeTime(), Side.SHORT, "SHORT_DUMP_BTC", decisionRegimeTag);
		}
		String longAction = longOpened ? "ENTER_LONG" : "NO_ENTRY";
		String longBlockReason = longEval.signal() && !longOpened ? "ORDER_NOT_OPENED" : longEval.blockReason();
		writeDecision(state, bar5m, longAction, "BASELINE_IMPULSE_RECLAIM",
				longBlockReason, metrics, longEval);
		writeShortDecision(state, bar5m, metrics, shortEval, shortOpened);
	}

	private void rollDay(SymbolState state, long closeTimeMs) {
		LocalDate day = Instant.ofEpochMilli(closeTimeMs).atZone(zoneId).toLocalDate();
		if (!Objects.equals(day, state.dayKey)) {
			state.dayKey = day;
			state.entriesToday = 0;
		}
	}

	private boolean isBaselinesReady(SymbolState state, Metrics metrics) {
		return state.seen5mCloses >= requiredWarmup5m
				&& metrics != null
				&& state.indicators.baselineIndicatorsSeeded();
	}


	static int resolveRequiredWarmup5m(EliteV1Properties props, WarmupProperties warmupProperties) {
		if (props.warmup() != null && props.warmup().enabled()) {
			return Math.max(props.warmup().candles5m(), 0);
		}
		return Math.max(warmupProperties.candles5m(), 0);
	}

	PreCheckAction evaluatePreChecks(boolean baselinesReady, Side positionSide) {
		if (!baselinesReady) {
			return new PreCheckAction(DecisionAction.INPUTS_NOT_READY, null);
		}
		if (positionSide != Side.NONE) {
			return new PreCheckAction(DecisionAction.IN_POSITION_NO_ENTRY, null);
		}
		return new PreCheckAction(DecisionAction.CONTINUE, null);
	}

	private LongSetupEval evaluateBaselineImpulseReclaim(SymbolState state, Metrics m, Candle c5) {
		List<String> failReasons = new ArrayList<>();
		long decisionCloseTimeMs = c5 == null ? 0L : c5.closeTime();
		Candle c1 = resolveDecisionAlignedLast1m(state, decisionCloseTimeMs);
		SymbolState btcState = states.get(BTC_SYMBOL);
		Candle b1 = btcState == null ? null : resolveDecisionAlignedLast1m(btcState, decisionCloseTimeMs);
		double ret1m = Double.NaN;
		double range1m = Double.NaN;
		double closePos1m = Double.NaN;
		double lowerWickRatio = Double.NaN;
		double bbWidth5m = m == null ? Double.NaN : m.bbWidth_5m;
		double range5m = Double.NaN;
		double high1h = Double.NaN;
		double fromHigh1h = Double.NaN;
		double btcRet1m = Double.NaN;

		if (state.positionSide != Side.NONE) {
			failReasons.add("IN_POSITION");
		}
		if (c1 == null || c5 == null || b1 == null || !Double.isFinite(bbWidth5m) || state.last5m.size() < 12) {
			failReasons.add("INPUTS_NOT_READY");
			return new LongSetupEval(false, "INPUTS_NOT_READY", failReasons, ret1m, range1m, closePos1m, lowerWickRatio,
					bbWidth5m, range5m, high1h, fromHigh1h, btcRet1m, Double.NaN, Double.NaN, Double.NaN);
		}

		ret1m = (c1.close() - c1.open()) / c1.open();
		range1m = (c1.high() - c1.low()) / c1.open();
		double candleRange1m = c1.high() - c1.low();
		if (candleRange1m <= 0.0) {
			failReasons.add("FAIL_1M_CLOSEPOS");
			failReasons.add("FAIL_1M_LOWERWICK");
		} else {
			closePos1m = (c1.close() - c1.low()) / candleRange1m;
			double lowerWick = Math.min(c1.open(), c1.close()) - c1.low();
			lowerWickRatio = lowerWick / candleRange1m;
		}
		if (ret1m < 0.0010) {
			failReasons.add("FAIL_1M_RET");
		}
		if (range1m < 0.0025) {
			failReasons.add("FAIL_1M_RANGE");
		}
		if (!Double.isFinite(closePos1m) || closePos1m < 0.90) {
			failReasons.add("FAIL_1M_CLOSEPOS");
		}
		if (!Double.isFinite(lowerWickRatio) || lowerWickRatio > 0.10) {
			failReasons.add("FAIL_1M_LOWERWICK");
		}

		range5m = (c5.high() - c5.low()) / c5.open();
		if (bbWidth5m < 0.018) {
			failReasons.add("FAIL_5M_BBWIDTH");
		}
		if (range5m < 0.0030) {
			failReasons.add("FAIL_5M_RANGE");
		}

		int i = 0;
		for (Candle candle : state.last5m) {
			if (state.last5m.size() - i <= 12) {
				high1h = Double.isFinite(high1h) ? Math.max(high1h, candle.high()) : candle.high();
			}
			i++;
		}
		fromHigh1h = (c5.close() - high1h) / high1h;
		if (fromHigh1h > -0.0080) {
			failReasons.add("FAIL_FROMHIGH_1H");
		}

		btcRet1m = (b1.close() - b1.open()) / b1.open();
		if (btcRet1m < 0.0008) {
			failReasons.add("FAIL_BTC_1M_RET");
		}

		double spreadPct = resolveSpreadPct(state.symbol);
		if (Double.isFinite(spreadPct) && spreadPct >= SPREAD_PCT_MAX) {
			failReasons.add("FAIL_SPREAD_PCT_GE_0_0012");
		}

		boolean fromHighVeto = Double.isFinite(fromHigh1h) && fromHigh1h <= LONG_FROM_HIGH_1H_MAX;
		boolean emaDistVeto = m != null && Double.isFinite(m.ema20DistPct) && m.ema20DistPct <= LONG_EMA20_DIST_MAX;
		if (fromHighVeto || emaDistVeto) {
			failReasons.add("VETO_LONG_DEEP_OR_NO_LAUNCH");
			if (fromHighVeto) {
				failReasons.add("fromHigh1h<=-0.02261");
			}
			if (emaDistVeto) {
				failReasons.add("ema20DistPct<=0.00234");
			}
		}

		double entryPrice = c5.close();
		double tickSize = resolveTickSize(state.symbol);
		double tpPrice = roundDown(entryPrice * (1.0 + TP_PCT), tickSize);
		double slPrice = roundDown(entryPrice * (1.0 - SL_PCT), tickSize);
		boolean signal = failReasons.isEmpty();
		String reason = signal ? "NONE" : String.join("|", failReasons);
		return new LongSetupEval(signal, reason, failReasons, ret1m, range1m, closePos1m, lowerWickRatio,
				bbWidth5m, range5m, high1h, fromHigh1h, btcRet1m, entryPrice, tpPrice, slPrice);
	}

	private LongSetupEval evaluateElitV1LongSetup(SymbolState state, Metrics m, Candle bar5m, String symbol, long nowMs) {
		return LongSetupEval.empty();
	}

	private ShortSetupEval evaluateShortDumpBtcSetup(SymbolState state, Candle bar5m, Metrics m) {
		List<String> failReasons = new ArrayList<>();
		long decisionCloseTimeMs = bar5m == null ? 0L : bar5m.closeTime();
		Candle coinBar1mLast = resolveDecisionAlignedLast1m(state, decisionCloseTimeMs);
		SymbolState btcState = states.get(BTC_SYMBOL);
		Candle btcBar1mLast = btcState == null ? null : resolveDecisionAlignedLast1m(btcState, decisionCloseTimeMs);
		double coinRet1 = Double.NaN;
		double btcRet1 = Double.NaN;
		double btcClosePos1 = Double.NaN;

		if (!isValidFinalCandle(coinBar1mLast) || !isValidFinalCandle(btcBar1mLast)) {
			failReasons.add("INPUTS_NOT_READY");
			return new ShortSetupEval(false, failReasons, coinRet1, btcRet1, btcClosePos1, Double.NaN, Double.NaN, Double.NaN);
		}
		if (state.positionSide != Side.NONE) {
			failReasons.add("IN_POSITION");
			return new ShortSetupEval(false, failReasons, coinRet1, btcRet1, btcClosePos1, Double.NaN, Double.NaN, Double.NaN);
		}

		coinRet1 = (coinBar1mLast.close() / coinBar1mLast.open()) - 1.0;
		btcRet1 = (btcBar1mLast.close() / btcBar1mLast.open()) - 1.0;
		double btcRange = btcBar1mLast.high() - btcBar1mLast.low();
		btcClosePos1 = btcRange == 0.0 ? 0.50 : (btcBar1mLast.close() - btcBar1mLast.low()) / btcRange;

		if (coinRet1 > -0.0050) {
			failReasons.add("FAIL_COIN_RET1");
		}
		if (btcRet1 > -0.0008) {
			failReasons.add("FAIL_BTC_RET1");
		}
		if (btcClosePos1 > 0.45) {
			failReasons.add("FAIL_BTC_CLOSEPOS1");
		}

		double bwRatio5m = m == null ? Double.NaN : m.bwRatio5m;
		double macdRatio5m = m == null ? Double.NaN : m.macdRatio5m;
		double atrRatio5m = m == null ? Double.NaN : m.atrRatio5m;
		double spreadPct = resolveSpreadPct(state.symbol);
		boolean shortVeto = Double.isFinite(bwRatio5m)
				&& Double.isFinite(macdRatio5m)
				&& Double.isFinite(atrRatio5m)
				&& bwRatio5m < SHORT_BW_RATIO_MAX
				&& macdRatio5m < SHORT_MACD_RATIO_MAX
				&& atrRatio5m < SHORT_ATR_RATIO_MAX;
		if (shortVeto) {
			failReasons.add("VETO_SHORT_WEAK_TREND_TRIO");
		}
		if (Double.isFinite(spreadPct) && spreadPct >= SPREAD_PCT_MAX) {
			failReasons.add("FAIL_SPREAD_PCT_GE_0_0012");
		}

		double next5mOpen = bar5m.close();
		double tickSize = resolveTickSize(state.symbol);
		double tpPrice = roundUp(next5mOpen * (1.0 - TP_PCT), tickSize);
		double slPrice = roundUp(next5mOpen * (1.0 + SL_PCT), tickSize);
		return new ShortSetupEval(failReasons.isEmpty(), failReasons, coinRet1, btcRet1, btcClosePos1, next5mOpen, tpPrice, slPrice);
	}

	private boolean isValidFinalCandle(Candle candle) {
		if (candle == null) {
			return false;
		}
		if (!Double.isFinite(candle.open()) || !Double.isFinite(candle.high()) || !Double.isFinite(candle.low()) || !Double.isFinite(candle.close())) {
			return false;
		}
		return candle.open() > 0.0;
	}

	private void writeShortDecision(SymbolState state, Candle bar5m, Metrics metrics, ShortSetupEval eval, boolean shortOpened) {
		ObjectNode node = objectMapper.createObjectNode();
		long timeMs = bar5m.closeTime();
		var timeTr = Instant.ofEpochMilli(timeMs).atZone(zoneId);
		LocalDate dayFromTimeMs = timeTr.toLocalDate();
		boolean baselinesReady = isBaselinesReady(state, metrics);
		node.put("type", "DECISION");
		node.put("symbol", state.symbol);
		node.put("strategy", "ELITE_V1");
		node.put("tfDecision", "5m");
		node.put("tfExecution", "1m");
		node.put("version", "20260207-elitev1-logv2");
		node.put("closeTimeMs", timeMs);
		node.put("closeTime", ISO_OFFSET_FMT.format(timeTr));
		node.put("timeMs", timeMs);
		node.put("timeUtc", Instant.ofEpochMilli(timeMs).toString());
		node.put("timeTr", ISO_OFFSET_FMT.format(timeTr));
		node.put("dayKey", DAY_FMT.format(dayFromTimeMs));
		node.put("entriesToday", state.entriesToday);
		node.put("baselinesReady", baselinesReady);
		node.put("globalOpenPositions", globalOpenPositions.get());
		putBar(node, "bar5m", bar5m, FIVE_MIN_MS);
		putOrderflow(node, bar5m);
		putLiquidity(node, state.symbol, timeMs);
		node.put("liquidityHealthAgeMs", resolveLiquidityHealthAgeMs());
		Candle last1m = resolveDecisionAlignedLast1m(state, timeMs);
		if (last1m != null) {
			putBar(node, "bar1mLast", last1m, ONE_MIN_MS);
		}
		putBars1mIn5m(node, state, timeMs);
		SymbolState btcState = states.get(BTC_SYMBOL);
		Candle btc1m = btcState == null ? null : resolveDecisionAlignedLast1m(btcState, timeMs);
		if (btc1m != null) {
			putBar(node, "btcBar1mLast", btc1m, ONE_MIN_MS);
		}
		List<String> invalidReasons = new ArrayList<>();
		if (!baselinesReady || metrics == null) {
			applyWarmupNotReadyFields(node, 0, state.seen1mCloses, requiredWarmup5m, state.seen5mCloses);
			node.with("warmup").put("baselinesSeeded", state.indicators.baselineIndicatorsSeeded());
		} else {
			node.put("rawRegimeTag", metrics.rawRegimeTag.name());
			node.put("activeRegimeTag", metrics.activeRegimeTag.name());
			ObjectNode metricNode = node.putObject("metrics");
			putMetric(metricNode, "bbWidth_5m", metrics.bbWidth_5m, invalidReasons, "bbWidth_5m");
			putMetric(metricNode, "bwEma_5m", metrics.bwEma_5m, invalidReasons, "bwEma_5m");
			putMetric(metricNode, "bwRatio_5m", metrics.bwRatio5m, invalidReasons, "bwRatio_5m");
			putMetric(metricNode, "volRatio", metrics.volRatio, invalidReasons, "volRatio");
			putMetric(metricNode, "volEma_5m", metrics.volEma_5m, invalidReasons, "volEma_5m");
			putMetric(metricNode, "volRatioOfEma", metrics.volRatioOfEma, invalidReasons, "volRatioOfEma");
			putMetric(metricNode, "ema20", metrics.ema20_5m, invalidReasons, "ema20");
			putMetric(metricNode, "ema20DistPct", metrics.ema20DistPct, invalidReasons, "ema20DistPct");
			putMetric(metricNode, "rsi9", metrics.rsi9_5m, invalidReasons, "rsi9");
			putMetric(metricNode, "bbLower", metrics.bbLower, invalidReasons, "bbLower");
			putMetric(metricNode, "bbMiddle", metrics.bbMiddle, invalidReasons, "bbMiddle");
			putMetric(metricNode, "bbUpper", metrics.bbUpper, invalidReasons, "bbUpper");
			putMetric(metricNode, "bbPercentB_5m", metrics.bbPercentB_5m, invalidReasons, "bbPercentB_5m");
			putMetric(metricNode, "ema20_5m", metrics.ema20_5m, invalidReasons, "ema20_5m");
			putMetric(metricNode, "macdDelta", metrics.macdDelta, invalidReasons, "macdDelta");
			putMetric(metricNode, "macdAbsEma_5m", metrics.macdAbsEma_5m, invalidReasons, "macdAbsEma_5m");
			putMetric(metricNode, "macdRatio_5m", metrics.macdRatio5m, invalidReasons, "macdRatio_5m");
			putMetric(metricNode, "atr14", metrics.atr14, invalidReasons, "atr14");
			putMetric(metricNode, "atrEma_5m", metrics.atrEma_5m, invalidReasons, "atrEma_5m");
			putMetric(metricNode, "atrRatio_5m", metrics.atrRatio5m, invalidReasons, "atrRatio_5m");
		}
		node.put("action", shortOpened ? "ENTER_SHORT" : "NO_ENTRY");
		node.put("matchedSetup", "SHORT_DUMP_BTC");
		node.put("blockReason", eval.pass() && !shortOpened ? "ORDER_NOT_OPENED" : (eval.pass() ? "NONE" : String.join("|", eval.failReasons())));
		node.put("inputsValid", eval.pass());
		var invalid = node.putArray("inputsInvalidReasons");
		for (String reason : eval.failReasons()) {
			invalid.add(reason);
		}
		node.put("elit.tpPct", TP_PCT);
		node.put("elit.slPct", SL_PCT);
		node.put("elit.lookaheadBars", LOOKAHEAD_BARS);
		node.putNull("elit.takerBuyRatio");
		node.putNull("elit.imbalance");
		node.put("elit.isDownTrend", false);
		node.put("shortEliteMatched", eval.pass());
		node.put("shortEliteMatchedSetup", eval.pass() ? "SHORT_DUMP_BTC" : "NA");
		var shortFail = node.putArray("shortEliteFailReasons");
		for (String reason : eval.failReasons()) {
			shortFail.add(reason);
		}
		node.put("setup", "SHORT_DUMP_BTC");
		node.put("pass", eval.pass());
		var failReasons = node.putArray("failReasons");
		for (String reason : eval.failReasons()) {
			failReasons.add(reason);
		}
		putFiniteOrNull(node, "coinRet1", eval.coinRet1());
		putFiniteOrNull(node, "btcRet1", eval.btcRet1());
		putFiniteOrNull(node, "btcClosePos1", eval.btcClosePos1());
		if (eval.pass()) {
			node.put("entryPrice", eval.entryPrice());
			node.put("tpPrice", eval.tpPrice());
			node.put("slPrice", eval.slPrice());
		}
		writer.write(decisionPath(state.symbol, dayFromTimeMs), node.toString(), false);
	}



	private boolean openPosition(SymbolState state,
			double entryPrice,
			long entryOpenTimeMs,
			Side side,
			String matchedSetup,
			RegimeTag activeRegimeTag) {
		double tickSize = resolveTickSize(state.symbol);
		var filters = symbolFilterService.getFilters(state.symbol);
		String bracketId = UUID.randomUUID().toString();
		double rawQty = Math.max(props.paperNotionalUsd() / Math.max(entryPrice, 1e-9), 1e-9);
		double qty = floorToStep(rawQty, filters == null || filters.stepSize() == null ? null : filters.stepSize().doubleValue());
		double minQty = filters == null || filters.minQty() == null ? 0.0 : filters.minQty().doubleValue();
		double minNotional = filters == null || filters.minNotional() == null ? 0.0 : filters.minNotional().doubleValue();
		if (qty < minQty || (qty * entryPrice) < minNotional || qty <= 0.0) {
			LOGGER.warn("EVENT=ENTRY_SKIPPED symbol={} reason=INPUTS_NOT_READY qty={} minQty={} notional={} minNotional={}",
					state.symbol, qty, minQty, qty * entryPrice, minNotional);
			return false;
		}
		String entryOrderClientId = "ELITE_ENTRY_" + state.symbol + "_" + bracketId;
		Long entryOrderId = null;

		if (props.mode() == EliteV1Properties.Mode.LIVE) {
			String entrySide = side == Side.SHORT ? "SELL" : "BUY";
			OrderResponse entryResponse = orderClient.placeMarketOrder(
					state.symbol,
					entrySide,
					BigDecimal.valueOf(qty),
					null,
					entryOrderClientId).block();
			if (entryResponse == null || entryResponse.orderId() == null) {
				LOGGER.warn("EVENT=ENTRY_FAILED symbol={} side={} reason=NULL_RESPONSE", state.symbol, side);
				return false;
			}
			entryOrderId = entryResponse.orderId();
			if (entryResponse.avgPrice() != null && entryResponse.avgPrice().doubleValue() > 0.0) {
				entryPrice = entryResponse.avgPrice().doubleValue();
			}
		}

		double tpRaw;
		double slRaw;
		double tpPrice;
		double slPrice;
		if (side == Side.SHORT) {
			tpRaw = entryPrice * (1.0 - TP_PCT);
			slRaw = entryPrice * (1.0 + SL_PCT);
			tpPrice = roundDown(tpRaw, tickSize);
			slPrice = roundUp(slRaw, tickSize);
		} else {
			tpRaw = entryPrice * (1.0 + TP_PCT);
			slRaw = entryPrice * (1.0 - SL_PCT);
			tpPrice = roundDown(tpRaw, tickSize);
			slPrice = roundDown(slRaw, tickSize);
		}
		if (side == Side.SHORT) {
			if (!(tpPrice < entryPrice)) {
				tpPrice = roundDown(Math.max(entryPrice - tickSize, tickSize), tickSize);
			}
			if (!(slPrice > entryPrice)) {
				slPrice = roundUp(entryPrice + tickSize, tickSize);
			}
		} else {
			if (!(tpPrice > entryPrice)) {
				tpPrice = roundUp(entryPrice + tickSize, tickSize);
			}
			if (!(slPrice < entryPrice)) {
				slPrice = roundDown(Math.max(entryPrice - tickSize, tickSize), tickSize);
			}
		}

		Long slOrderId = null;
		Long tpOrderId = null;
		String slClientOrderId = "ELITE_SL_" + state.symbol + "_" + bracketId;
		String tpClientOrderId = "ELITE_TP_" + state.symbol + "_" + bracketId;

		state.positionSide = side;
		state.entryPrice = entryPrice;
		state.qty = qty;
		state.entryTimeMs = entryOpenTimeMs;
		state.tpPrice = tpPrice;
		state.slPrice = slPrice;
		state.tpHitTimeMs = null;
		state.slHitTimeMs = null;
		state.tpHitBar1m = null;
		state.slHitBar1m = null;
		state.bracketId = bracketId;
		state.entryOrderId = entryOrderId;
		state.entryClientOrderId = entryOrderClientId;
		state.slOrderId = null;
		state.slClientOrderId = slClientOrderId;
		state.tpOrderId = null;
		state.tpClientOrderId = tpClientOrderId;
		state.entriesToday++;
		globalOpenPositions.incrementAndGet();
		if (state.dayKey == null) {
			state.dayKey = Instant.ofEpochMilli(entryOpenTimeMs).atZone(zoneId).toLocalDate();
		}

		if (props.mode() == EliteV1Properties.Mode.LIVE) {
			String exitSide = side == Side.SHORT ? "BUY" : "SELL";
			OrderResponse slResponse = orderClient.placeStopMarketClosePositionOrder(
					state.symbol,
					exitSide,
					BigDecimal.valueOf(slPrice),
					"MARK_PRICE",
					slClientOrderId).block();
			OrderResponse tpResponse = orderClient.placeReduceOnlyLimitOrder(
					state.symbol,
					exitSide,
					BigDecimal.valueOf(qty),
					BigDecimal.valueOf(tpPrice),
					null,
					"GTC",
					tpClientOrderId).block();
			if (slResponse == null || slResponse.orderId() == null || tpResponse == null || tpResponse.orderId() == null) {
				LOGGER.error("EVENT=BRACKET_PLACE_FAIL_UNPROTECTED symbol={} bracketId={} entryOrderId={} side={} qty={} entryPrice={} tpPrice={} slPrice={}",
						state.symbol,
						bracketId,
						entryOrderId,
						side,
						qty,
						entryPrice,
						tpPrice,
						slPrice);
			} else {
				slOrderId = slResponse.orderId();
				tpOrderId = tpResponse.orderId();
				state.slOrderId = slOrderId;
				state.tpOrderId = tpOrderId;
			}
		}

		ObjectNode node = objectMapper.createObjectNode();
		node.put("type", "ENTRY");
		node.put("symbol", state.symbol);
		node.put("time", Instant.ofEpochMilli(entryOpenTimeMs).toString());
		node.put("side", side.name());
		node.put("entryPrice", entryPrice);
		node.put("qty", qty);
		node.put("tpPrice", tpPrice);
		node.put("slPrice", slPrice);
		node.put("tpRaw", tpRaw);
		node.put("slRaw", slRaw);
		node.put("tickSize", tickSize);
		node.put("matchedSetup", matchedSetup);
		node.put("activeRegimeTag", activeRegimeTag.name());
		node.put("elit.tpPct", TP_PCT);
		node.put("elit.slPct", SL_PCT);
		node.put("elit.lookaheadBars", LOOKAHEAD_BARS);
		writer.write(tradePath(state.symbol, state.dayKey), node.toString(), true);
		return true;
	}



	private void tryPlaceMissingLiveBrackets(SymbolState state) {
		if (props.mode() != EliteV1Properties.Mode.LIVE || state.positionSide == Side.NONE || state.bracketId == null) {
			return;
		}
		String exitSide = state.positionSide == Side.SHORT ? "BUY" : "SELL";
		if (state.slOrderId == null) {
			OrderResponse slResponse = orderClient.placeStopMarketClosePositionOrder(
					state.symbol,
					exitSide,
					BigDecimal.valueOf(state.slPrice),
					"MARK_PRICE",
					state.slClientOrderId).block();
			if (slResponse != null && slResponse.orderId() != null) {
				state.slOrderId = slResponse.orderId();
			}
		}
		if (state.tpOrderId == null) {
			OrderResponse tpResponse = orderClient.placeReduceOnlyLimitOrder(
					state.symbol,
					exitSide,
					BigDecimal.valueOf(state.qty),
					BigDecimal.valueOf(state.tpPrice),
					null,
					"GTC",
					state.tpClientOrderId).block();
			if (tpResponse != null && tpResponse.orderId() != null) {
				state.tpOrderId = tpResponse.orderId();
			}
		}
		if (state.slOrderId == null || state.tpOrderId == null) {
			LOGGER.error("EVENT=BRACKET_RECOVERY_FAIL symbol={} bracketId={} hasSl={} hasTp={}",
					state.symbol,
					state.bracketId,
					state.slOrderId != null,
					state.tpOrderId != null);
		}
	}


	private boolean forceLiveTimeoutExit(SymbolState state, Candle bar) {
		if (props.mode() != EliteV1Properties.Mode.LIVE || state.positionSide == Side.NONE) {
			return false;
		}
		try {
			String exitSide = state.positionSide == Side.SHORT ? "BUY" : "SELL";
			String timeoutClientId = "ELITE_TO_" + state.symbol + "_" + state.bracketId;
			OrderResponse closeResponse = orderClient.placeReduceOnlyMarketOrder(
					state.symbol,
					exitSide,
					BigDecimal.valueOf(state.qty),
					null,
					timeoutClientId).block();
			if (closeResponse == null || closeResponse.orderId() == null) {
				LOGGER.error("EVENT=LIVE_TIMEOUT_CLOSE_FAIL symbol={} bracketId={} side={} qty={}",
						state.symbol,
						state.bracketId,
						state.positionSide,
						state.qty);
				return false;
			}
			if (state.slOrderId != null) {
				try { orderClient.cancelOrder(state.symbol, state.slOrderId).block(); } catch (Exception ignored) {}
			}
			if (state.tpOrderId != null) {
				try { orderClient.cancelOrder(state.symbol, state.tpOrderId).block(); } catch (Exception ignored) {}
			}
			double exitPrice = closeResponse.avgPrice() != null && closeResponse.avgPrice().doubleValue() > 0.0
					? closeResponse.avgPrice().doubleValue()
					: bar.close();
			exitPosition(state, ExitReason.TIMEOUT_36B, exitPrice, bar.closeTime(),
					new ExitEvaluation(ExitReason.TIMEOUT_36B, "NONE", "TIMEOUT_36B_LIVE", null, true));
			return true;
		} catch (Exception ex) {
			LOGGER.error("EVENT=LIVE_TIMEOUT_CLOSE_EXCEPTION symbol={} bracketId={} message={}",
					state.symbol,
					state.bracketId,
					ex.getMessage());
			return false;
		}
	}


	private boolean checkLiveBracketExit(SymbolState state, Candle oneMinuteBar) {
		if (state.positionSide == Side.NONE || state.bracketId == null) {
			return false;
		}
		if ((oneMinuteBar.closeTime() - state.entryTimeMs) >= LOOKAHEAD_MS) {
			return forceLiveTimeoutExit(state, oneMinuteBar);
		}
		if (state.slOrderId == null || state.tpOrderId == null) {
			tryPlaceMissingLiveBrackets(state);
			if (state.slOrderId == null || state.tpOrderId == null) {
				return false;
			}
		}
		Map<Long, BinanceFuturesOrderClient.OpenOrder> openOrders = orderClient.fetchOpenOrders(state.symbol).block();
		if (openOrders == null) {
			return false;
		}
		boolean slOpen = openOrders.containsKey(state.slOrderId);
		boolean tpOpen = openOrders.containsKey(state.tpOrderId);
		if (slOpen && tpOpen) {
			return false;
		}
		if (!slOpen) {
			OrderResponse sl = orderClient.fetchOrder(state.symbol, state.slOrderId).block();
			if (sl != null && "FILLED".equalsIgnoreCase(sl.status())) {
				handleBracketFill(state, "SL_ORDER_FILLED", state.slOrderId, state.slClientOrderId, state.tpOrderId, sl, oneMinuteBar.closeTime());
				return true;
			}
		}
		if (!tpOpen) {
			OrderResponse tp = orderClient.fetchOrder(state.symbol, state.tpOrderId).block();
			if (tp != null && "FILLED".equalsIgnoreCase(tp.status())) {
				handleBracketFill(state, "TP_ORDER_FILLED", state.tpOrderId, state.tpClientOrderId, state.slOrderId, tp, oneMinuteBar.closeTime());
				return true;
			}
		}
		return false;
	}

	private void handleBracketFill(SymbolState state,
			String reason,
			Long exitOrderId,
			String exitClientOrderId,
			Long otherOrderId,
			OrderResponse filledOrder,
			long closeTimeMs) {
		if (otherOrderId != null) {
			try {
				orderClient.cancelOrder(state.symbol, otherOrderId).block();
				LOGGER.info("EVENT=BRACKET_CANCEL_OTHER symbol={} canceledOrderId={}", state.symbol, otherOrderId);
			} catch (Exception ignored) {
			}
		}
		double exitPrice = filledOrder != null && filledOrder.avgPrice() != null && filledOrder.avgPrice().doubleValue() > 0
				? filledOrder.avgPrice().doubleValue()
				: ("TP_ORDER_FILLED".equals(reason) ? state.tpPrice : state.slPrice);
		LOGGER.info("EVENT=BRACKET_EXIT symbol={} by={} exitOrderId={}", state.symbol, reason, exitOrderId);
		ExitReason exitReason = "TP_ORDER_FILLED".equals(reason) ? ExitReason.TP_ORDER_FILLED : ExitReason.SL_ORDER_FILLED;
		exitPosition(state, exitReason, exitPrice, closeTimeMs,
				new ExitEvaluation(exitReason, "NONE", reason, null, false));
	}

	private void checkPaperExit(SymbolState state, Candle oneMinuteBar) {
		if (state == null || oneMinuteBar == null) {
			return;
		}
		if (state.positionSide == Side.NONE || state.bracketId == null) {
			return;
		}
		// no-op: paper exits are evaluated on final 5m bars via checkPaperExitOnFiveMinute
	}

	private boolean checkPaperExitOnFiveMinute(SymbolState state, Candle bar5m) {
		if (state == null || bar5m == null || state.positionSide == Side.NONE || state.bracketId == null) {
			return false;
		}
		if (state.positionSide == Side.LONG) {
			if (bar5m.low() <= state.slPrice) {
				exitPosition(state, ExitReason.STOP_LOSS, state.slPrice, bar5m.closeTime(),
						new ExitEvaluation(ExitReason.STOP_LOSS, "SL_FIRST", "SL", null, false));
				return true;
			}
			if (bar5m.high() >= state.tpPrice) {
				exitPosition(state, ExitReason.TAKE_PROFIT, state.tpPrice, bar5m.closeTime(),
						new ExitEvaluation(ExitReason.TAKE_PROFIT, "TP_FIRST", "TP", null, false));
				return true;
			}
		} else if (state.positionSide == Side.SHORT) {
			if (bar5m.high() >= state.slPrice) {
				exitPosition(state, ExitReason.STOP_LOSS, state.slPrice, bar5m.closeTime(),
						new ExitEvaluation(ExitReason.STOP_LOSS, "SL_FIRST", "SL", null, false));
				return true;
			}
			if (bar5m.low() <= state.tpPrice) {
				exitPosition(state, ExitReason.TAKE_PROFIT, state.tpPrice, bar5m.closeTime(),
						new ExitEvaluation(ExitReason.TAKE_PROFIT, "TP_FIRST", "TP", null, false));
				return true;
			}
		}
		if ((bar5m.closeTime() - state.entryTimeMs) >= LOOKAHEAD_MS) {
			exitPosition(state, ExitReason.TIMEOUT_36B, bar5m.close(), bar5m.closeTime(),
					new ExitEvaluation(ExitReason.TIMEOUT_36B, "NONE", "TIMEOUT_36B", null, true));
			return true;
		}
		return false;
	}

	private void exitPosition(SymbolState state, ExitReason reason, double exitPrice, long exitTimeMs, ExitEvaluation evaluation) {
		double pnl = state.positionSide == Side.LONG
				? (exitPrice - state.entryPrice) * state.qty
				: (state.entryPrice - exitPrice) * state.qty;

		ObjectNode node = objectMapper.createObjectNode();
		node.put("type", "EXIT");
		node.put("symbol", state.symbol);
		node.put("time", Instant.ofEpochMilli(exitTimeMs).toString());
		node.put("side", state.positionSide.name());
		node.put("entryPrice", state.entryPrice);
		node.put("exitPrice", exitPrice);
		node.put("qty", state.qty);
		node.put("realizedPnl", pnl);
		node.put("exitReason", reason.name());
		node.put("tpPrice", state.tpPrice);
		node.put("slPrice", state.slPrice);
		if (state.tpHitTimeMs != null) {
			node.put("tpHitTimeMs", state.tpHitTimeMs);
		} else {
			node.putNull("tpHitTimeMs");
		}
		if (state.slHitTimeMs != null) {
			node.put("slHitTimeMs", state.slHitTimeMs);
		} else {
			node.putNull("slHitTimeMs");
		}
		node.put("firstHit", evaluation == null ? "NONE" : evaluation.firstHit);
		node.put("exitTrigger", evaluation == null ? "TIMEOUT_36B" : evaluation.exitTrigger);
		node.put("elit.timeoutLoss", evaluation != null && evaluation.timeoutLoss);
		writer.write(tradePath(state.symbol, state.dayKey), node.toString(), true);

		state.positionSide = Side.NONE;
		state.bracketId = null;
		state.tpHitTimeMs = null;
		state.slHitTimeMs = null;
		state.tpHitBar1m = null;
		state.slHitBar1m = null;
		state.entryOrderId = null;
		state.entryClientOrderId = null;
		state.slOrderId = null;
		state.slClientOrderId = null;
		state.tpOrderId = null;
		state.tpClientOrderId = null;
		globalOpenPositions.updateAndGet(v -> Math.max(0, v - 1));

	}



	private void writeDecision(SymbolState state,
			Candle bar5m,
			String action,
			String matchedSetup,
			String blockReason,
			Metrics metrics,
			LongSetupEval longSetupEval) {
		ObjectNode node = objectMapper.createObjectNode();
		long timeMs = bar5m.closeTime();
		var timeTr = Instant.ofEpochMilli(timeMs).atZone(zoneId);
		LocalDate dayFromTimeMs = timeTr.toLocalDate();
		boolean baselinesReady = isBaselinesReady(state, metrics);
		node.put("type", "DECISION");
		node.put("symbol", state.symbol);
		node.put("strategy", "ELITE_V1");
		node.put("tfDecision", "5m");
		node.put("tfExecution", "1m");
		node.put("version", "20260207-elitev1-logv2");
		node.put("closeTimeMs", timeMs);
		node.put("closeTime", ISO_OFFSET_FMT.format(timeTr));
		node.put("timeMs", timeMs);
		node.put("timeUtc", Instant.ofEpochMilli(timeMs).toString());
		node.put("timeTr", ISO_OFFSET_FMT.format(timeTr));
		node.put("dayKey", DAY_FMT.format(dayFromTimeMs));
		node.put("entriesToday", state.entriesToday);
		node.put("baselinesReady", baselinesReady);
		node.put("globalOpenPositions", globalOpenPositions.get());
		putBar(node, "bar5m", bar5m, FIVE_MIN_MS);
		putOrderflow(node, bar5m);
		putLiquidity(node, state.symbol, timeMs);
		node.put("liquidityHealthAgeMs", resolveLiquidityHealthAgeMs());
		Candle last1m = resolveDecisionAlignedLast1m(state, timeMs);
		if (last1m != null) {
			putBar(node, "bar1mLast", last1m, ONE_MIN_MS);
		}
		putBars1mIn5m(node, state, timeMs);
		List<String> invalidReasons = new ArrayList<>();
		if (!baselinesReady || metrics == null) {
			applyWarmupNotReadyFields(node, 0, state.seen1mCloses, requiredWarmup5m, state.seen5mCloses);
			node.with("warmup").put("baselinesSeeded", state.indicators.baselineIndicatorsSeeded());
		} else {
			node.put("rawRegimeTag", metrics.rawRegimeTag.name());
			node.put("activeRegimeTag", metrics.activeRegimeTag.name());
			ObjectNode metricNode = node.putObject("metrics");
			putMetric(metricNode, "bbWidth_5m", metrics.bbWidth_5m, invalidReasons, "bbWidth_5m");
			putMetric(metricNode, "bwEma_5m", metrics.bwEma_5m, invalidReasons, "bwEma_5m");
			putMetric(metricNode, "bwRatio_5m", metrics.bwRatio5m, invalidReasons, "bwRatio_5m");
			putMetric(metricNode, "volRatio", metrics.volRatio, invalidReasons, "volRatio");
			putMetric(metricNode, "volEma_5m", metrics.volEma_5m, invalidReasons, "volEma_5m");
			putMetric(metricNode, "volRatioOfEma", metrics.volRatioOfEma, invalidReasons, "volRatioOfEma");
			putMetric(metricNode, "ema20", metrics.ema20_5m, invalidReasons, "ema20");
			putMetric(metricNode, "ema20DistPct", metrics.ema20DistPct, invalidReasons, "ema20DistPct");
			putMetric(metricNode, "rsi9", metrics.rsi9_5m, invalidReasons, "rsi9");
			putMetric(metricNode, "bbLower", metrics.bbLower, invalidReasons, "bbLower");
			putMetric(metricNode, "bbMiddle", metrics.bbMiddle, invalidReasons, "bbMiddle");
			putMetric(metricNode, "bbUpper", metrics.bbUpper, invalidReasons, "bbUpper");
			putMetric(metricNode, "bbPercentB_5m", metrics.bbPercentB_5m, invalidReasons, "bbPercentB_5m");
			putMetric(metricNode, "ema20_5m", metrics.ema20_5m, invalidReasons, "ema20_5m");
			putMetric(metricNode, "macdDelta", metrics.macdDelta, invalidReasons, "macdDelta");
			putMetric(metricNode, "macdAbsEma_5m", metrics.macdAbsEma_5m, invalidReasons, "macdAbsEma_5m");
			putMetric(metricNode, "macdRatio_5m", metrics.macdRatio5m, invalidReasons, "macdRatio_5m");
			putMetric(metricNode, "atr14", metrics.atr14, invalidReasons, "atr14");
			putMetric(metricNode, "atrEma_5m", metrics.atrEma_5m, invalidReasons, "atrEma_5m");
			putMetric(metricNode, "atrRatio_5m", metrics.atrRatio5m, invalidReasons, "atrRatio_5m");
		}
		LongSetupEval eval = longSetupEval == null ? LongSetupEval.empty() : longSetupEval;
		node.put("action", eval.signal() ? "ENTER_LONG" : action);
		node.put("matchedSetup", eval.signal() ? "BASELINE_IMPULSE_RECLAIM" : matchedSetup);
		node.put("blockReason", eval.signal() ? "NONE" : resolveDecisionBlockReason(action, eval.blockReason() == null ? blockReason : eval.blockReason()));
		node.put("inputsValid", eval.signal());
		var invalid = node.putArray("inputsInvalidReasons");
		for (String reason : eval.failReasons()) {
			invalid.add(reason);
		}
		node.put("elit.tpPct", TP_PCT);
		node.put("elit.slPct", SL_PCT);
		node.put("elit.lookaheadBars", LOOKAHEAD_BARS);
		node.putNull("elit.takerBuyRatio");
		node.putNull("elit.imbalance");
		node.put("elit.isDownTrend", false);
		node.put("shortEliteMatched", false);
		node.putNull("shortEliteMatchedSetup");
		node.putArray("shortEliteFailReasons");
		node.put("setup", "BASELINE_IMPULSE_RECLAIM");
		node.put("pass", eval.signal());
		var failReasons = node.putArray("failReasons");
		for (String reason : eval.failReasons()) {
			failReasons.add(reason);
		}
		putFiniteOrNull(node, "ret1m", eval.ret1m());
		putFiniteOrNull(node, "range1m", eval.range1m());
		putFiniteOrNull(node, "closePos1m", eval.closePos1m());
		putFiniteOrNull(node, "lowerWickRatio", eval.lowerWickRatio());
		putFiniteOrNull(node, "bbWidth_5m", eval.bbWidth5m());
		putFiniteOrNull(node, "range5m", eval.range5m());
		putFiniteOrNull(node, "high1h", eval.high1h());
		putFiniteOrNull(node, "fromHigh_1h", eval.fromHigh1h());
		putFiniteOrNull(node, "btcRet1m", eval.btcRet1m());
		if (eval.signal()) {
			node.put("entryPrice", eval.entryPrice());
			node.put("tpPrice", eval.tpPrice());
			node.put("slPrice", eval.slPrice());
		}
		writer.write(decisionPath(state.symbol, dayFromTimeMs), node.toString(), false);
	}


	private void putBars1mIn5m(ObjectNode node, SymbolState state, long closeTimeMs) {
		long fromCloseMs = closeTimeMs - (4L * ONE_MIN_MS);
		var bars = node.putArray("bars1mIn5m");
		for (Candle c : state.last1m) {
			if (c.closeTime() < fromCloseMs || c.closeTime() > closeTimeMs) {
				continue;
			}
			ObjectNode b = bars.addObject();
			b.put("open", c.open());
			b.put("high", c.high());
			b.put("low", c.low());
			b.put("close", c.close());
			b.put("volume", c.volume());
			b.put("closeTimeMs", c.closeTime());
			b.put("openTimeMs", c.closeTime() - (ONE_MIN_MS - 1));
		}
	}

	private double resolveSpreadPct(String symbol) {
		var snap = bookTickerStreamWatcher.getSnapshot(symbol);
		if (snap == null) {
			return Double.NaN;
		}
		double bid = snap.bestBidPrice();
		double ask = snap.bestAskPrice();
		double mid = (bid + ask) / 2.0;
		if (mid <= 0.0) {
			return Double.NaN;
		}
		return (ask - bid) / mid;
	}


	private static void putFiniteOrNull(ObjectNode node, String key, double value) {
		if (Double.isFinite(value)) {
			node.put(key, value);
		} else {
			node.putNull(key);
		}
	}

	private Candle resolveDecisionAlignedLast1m(SymbolState state, long decisionCloseTimeMs) {
		if (state == null || state.last1m.isEmpty()) {
			return null;
		}
		for (var it = state.last1m.descendingIterator(); it.hasNext(); ) {
			Candle candle = it.next();
			if (candle != null
					&& candle.closeTime() <= decisionCloseTimeMs
					&& (decisionCloseTimeMs - candle.closeTime()) < ONE_MIN_MS) {
				return candle;
			}
		}
		return null;
	}


	static String resolveDecisionBlockReason(String action, String blockReason) {
		if (blockReason != null && !blockReason.isBlank()) {
			return blockReason;
		}
		if ("ENTER_LONG".equals(action)) {
			return "NONE";
		}
		if ("NO_ENTRY".equals(action)) {
			return "NO_ENTRY";
		}
		if ("INPUTS_NOT_READY".equals(action)) {
			return "INPUTS_NOT_READY";
		}
		if ("IN_POSITION_NO_ENTRY".equals(action)) {
			return "IN_POSITION_NO_ENTRY";
		}
		return (action == null || action.isBlank()) ? "UNKNOWN" : action;
	}

	private static void putBar(ObjectNode parent, String field, Candle c, long tfMs) {
		ObjectNode b = parent.putObject(field);
		b.put("open", c.open());
		b.put("high", c.high());
		b.put("low", c.low());
		b.put("close", c.close());
		b.put("volume", c.volume());
		long closeTimeMs = c.closeTime();
		b.put("closeTimeMs", closeTimeMs);
		b.put("openTimeMs", closeTimeMs - tfMs + 1);
	}

	private void putOrderflow(ObjectNode parent, Candle bar5m) {
		ObjectNode orderflow = parent.putObject("orderflow5m");
		orderflow.put("baseVolume", bar5m.volume());
		OrderflowSnapshot of = OrderflowSnapshot.fromCandle(bar5m);
		if (of == null || !of.available() || bar5m.volume() <= 0.0) {
			orderflow.put("reason", "KLINE_TAKER_FIELDS_MISSING");
			orderflow.putNull("quoteVolume");
			orderflow.putNull("trades");
			orderflow.putNull("takerBuyBase");
			orderflow.putNull("takerBuyQuote");
			orderflow.putNull("takerSellBase");
			orderflow.putNull("deltaBase");
			orderflow.putNull("takerBuyRatio");
			return;
		}

		double takerBuyBase = of.takerBuyBaseVolume();
		double takerSellBase = bar5m.volume() - takerBuyBase;
		double deltaBase = takerBuyBase - takerSellBase;
		orderflow.put("quoteVolume", of.quoteVolume());
		orderflow.put("trades", of.tradeCount());
		orderflow.put("takerBuyBase", takerBuyBase);
		orderflow.put("takerBuyQuote", of.takerBuyQuoteVolume());
		orderflow.put("takerSellBase", takerSellBase);
		orderflow.put("deltaBase", deltaBase);
		orderflow.put("takerBuyRatio", takerBuyBase / bar5m.volume());
		orderflow.put("reason", "OK");
	}

	// --- DEĞİŞECEK YER 3: putLiquidity Metodu ---
	private void putLiquidity(ObjectNode parent, String symbol, long nowMs) {
		// Doğrudan yeni watcher üzerinden snapshot alıyoruz
		var snap = bookTickerStreamWatcher.getSnapshot(symbol);

		if (snap == null) {
			parent.put("liqOk", false);
			return;
		}

		// Zaman farkını ve verileri hesapla
		long ageMs = Math.max(0L, nowMs - snap.eventTimeMs());
		double bid = snap.bestBidPrice();
		double ask = snap.bestAskPrice();
		double bidQty = snap.bestBidQty();
		double askQty = snap.bestAskQty();

		double mid = (bid + ask) / 2.0;
		double spreadPct = (mid <= 0) ? 0.0 : (ask - bid) / mid;
		double denom = (bidQty + askQty);
		double imbalance = (denom <= 0) ? 0.0 : (bidQty - askQty) / denom;

		// JSON objesine ekle
		parent.put("liqOk", true);
		parent.put("liqAgeMs", ageMs);
		parent.put("bid", bid);
		parent.put("ask", ask);
		parent.put("bidQty", bidQty);
		parent.put("askQty", askQty);
		parent.put("spreadPct", spreadPct);
		parent.put("imbalance", imbalance);
	}

	// --- DEĞİŞECEK YER 4: Yardımcı Metotlar ---
	private void checkLiquidityHealth() {
		// Artık yavaş metod çağırma (invoke) yok, doğrudan erişim var
		long last = bookTickerStreamWatcher.lastMsgMs();
		long age = System.currentTimeMillis() - last;
		if (age > 30000) {
			LOGGER.warn("Likidite verisi çok eski! Age: {}ms", age);
		}
	}

	private long resolveLiquidityHealthAgeMs() {
		try {
			Class<?> watcherClass = Class.forName("com.binance.strategy.BookTickerStreamWatcher");
			Object bean = applicationContext.getBean(watcherClass);
			Method lastMsgMsMethod = watcherClass.getMethod("lastMsgMs");
			Object value = lastMsgMsMethod.invoke(bean);
			if (value instanceof Number number) {
				long last = number.longValue();
				if (last <= 0L) {
					return -1L;
				}
				return Math.max(0L, System.currentTimeMillis() - last);
			}
		} catch (Exception ignored) {
		}
		return -1L;
	}

	private void startBookTickerWatcher() {
		try {
			Class<?> watcherClass = Class.forName("com.binance.strategy.BookTickerStreamWatcher");
			Object bean = applicationContext.getBean(watcherClass);
			Method startMethod = watcherClass.getMethod("start");
			startMethod.invoke(bean);
		} catch (Exception ex) {
			LOGGER.debug("EVENT=LIQUIDITY_WATCHER_START_SKIPPED reason={}", ex.toString());
		}
	}

	static void applyWarmupNotReadyFields(ObjectNode node, int required1mBars, long have1mBars, int required5mBars, long have5mBars) {
		node.put("rawRegimeTag", "UNKNOWN");
		node.put("activeRegimeTag", "UNKNOWN");
		node.putNull("metrics");
		ObjectNode warmup = node.putObject("warmup");
		warmup.put("required1mBars", required1mBars);
		warmup.put("have1mBars", have1mBars);
		warmup.put("missing1mBars", Math.max(0L, required1mBars - have1mBars));
		warmup.put("required5mBars", required5mBars);
		warmup.put("have5mBars", have5mBars);
		warmup.put("missing5mBars", Math.max(0L, required5mBars - have5mBars));
		node.put("inputsValid", false);
		var invalid = node.putArray("inputsInvalidReasons");
		invalid.add("WARMUP");
	}

	static void putMetric(ObjectNode metricNode, String key, double value, List<String> invalidReasons, String reasonKey) {
		if (Double.isFinite(value)) {
			metricNode.put(key, value);
		} else {
			metricNode.putNull(key);
			invalidReasons.add(reasonKey);
		}
	}

private Path decisionPath(String symbol, LocalDate day) {
		return DECISION_DIR.resolve(symbol + "-" + DAY_FMT.format(day) + ".jsonl");
	}

	private Path tradePath(String symbol, LocalDate day) {
		return TRADE_DIR.resolve(symbol + "-" + DAY_FMT.format(day) + ".jsonl");
	}


	private double resolveTickSize(String symbol) {
		var filters = symbolFilterService.getFilters(symbol);
		if (filters == null || filters.tickSize() == null) {
			return DEFAULT_TICK_SIZE;
		}
		return filters.tickSize().doubleValue();
	}

	static ExitReason resolveTouchExit(Side side,
			double tpPrice,
			double slPrice,
			Candle oneMinuteBar,
			EliteV1Properties.ConflictResolution conflictResolution) {
		boolean touchedTp;
		boolean touchedSl;
		if (side == Side.LONG) {
			touchedTp = oneMinuteBar.high() >= tpPrice;
			touchedSl = oneMinuteBar.low() <= slPrice;
		} else if (side == Side.SHORT) {
			touchedTp = oneMinuteBar.low() <= tpPrice;
			touchedSl = oneMinuteBar.high() >= slPrice;
		} else {
			return null;
		}
		if (!touchedTp && !touchedSl) {
			return null;
		}
		if (touchedTp && touchedSl) {
			return conflictResolution == EliteV1Properties.ConflictResolution.SL_FIRST
					? ExitReason.STOP_LOSS
					: ExitReason.TAKE_PROFIT;
		}
		return touchedSl ? ExitReason.STOP_LOSS : ExitReason.TAKE_PROFIT;
	}

	static double roundUp(double raw, double tickSize) {
		BigDecimal tick = BigDecimal.valueOf(tickSize);
		return BigDecimal.valueOf(raw)
				.divide(tick, 0, RoundingMode.CEILING)
				.multiply(tick)
				.doubleValue();
	}

	static double roundDown(double raw, double tickSize) {
		BigDecimal tick = BigDecimal.valueOf(tickSize);
		return BigDecimal.valueOf(raw)
				.divide(tick, 0, RoundingMode.FLOOR)
				.multiply(tick)
				.doubleValue();
	}

	static double floorToStep(double raw, Double stepSize) {
		if (stepSize == null || !Double.isFinite(stepSize) || stepSize <= 0.0) {
			return raw;
		}
		BigDecimal step = BigDecimal.valueOf(stepSize);
		return BigDecimal.valueOf(raw)
				.divide(step, 0, RoundingMode.FLOOR)
				.multiply(step)
				.doubleValue();
	}

	static RegimeTag rawRegime(double bwRatio, double macdRatio, double chopBwRatioMax, double chopMacdRatioMax) {
		// CHOP: her iki koşul düşük olmalı (orijinal mantık korundu)
		if (bwRatio < chopBwRatioMax && macdRatio < chopMacdRatioMax) {
			return RegimeTag.CHOP;
		}
		// TREND: her iki koşul da güçlü olmalı (FIX: eskiden OR mantığıydı → tek yüksek değer TREND sayılıyordu)
		// bwRatio >= chopBwRatioMax → BB genişliyor (volatilite artışı)
		// macdRatio >= chopMacdRatioMax → momentum güçlü
		// Her ikisi birden güçlü değilse CHOP'a dön (belirsiz/yarı-trend = CHOP gibi davran)
		if (bwRatio >= chopBwRatioMax && macdRatio >= chopMacdRatioMax) {
			return RegimeTag.TREND;
		}
		return RegimeTag.CHOP;
	}

	private void validateConfig() {
		if (props.paperNotionalUsd() <= 0) {
			throw new IllegalStateException("paperNotionalUsd must be > 0");
		}
	}

	enum Side {
		NONE,
		LONG,
		SHORT
	}

	enum RegimeTag {
		CHOP,
		TREND
	}

	enum ExitReason {
		TAKE_PROFIT,
		STOP_LOSS,
		TIMEOUT_36B,
		TP_ORDER_FILLED,
		SL_ORDER_FILLED
	}

	enum WarmupMode {
		DERIVE_5M_FROM_1M,
		DIRECT_5M
	}


	public record WarmupReadiness(String symbol, boolean ready, long have1m, int required1m, long have5m, int required5m, String reason) {
		static WarmupReadiness ready(String symbol, long have1m, int required1m, long have5m, int required5m) {
			return new WarmupReadiness(symbol, true, have1m, required1m, have5m, required5m, "READY");
		}

		static WarmupReadiness notReady(String symbol, long have1m, int required1m, long have5m, int required5m, String reason) {
			return new WarmupReadiness(symbol, false, have1m, required1m, have5m, required5m, reason);
		}

		static WarmupReadiness statusNull(String symbol) {
			return new WarmupReadiness(symbol, false, 0, 0, 0, 0, "STATUS_NULL");
		}
	}

		enum DecisionAction {
		CONTINUE,
		INPUTS_NOT_READY,
		IN_POSITION_NO_ENTRY,
		GLOBAL_MAX_OPEN_POSITIONS
	}

	record PreCheckAction(DecisionAction action, String blockReason) {
	}

	private record ExitEvaluation(ExitReason exitReason, String firstHit, String exitTrigger, String ambiguityRule, boolean timeoutLoss) {
	}

	private record HitSnapshot(double open, double high, double low, double close, double volume, long closeTimeMs) {
		static HitSnapshot fromCandle(Candle c) {
			return new HitSnapshot(c.open(), c.high(), c.low(), c.close(), c.volume(), c.closeTime());
		}
	}

	private record OrderflowSnapshot(Double quoteVolume, Long tradeCount, Double takerBuyBaseVolume,
			Double takerBuyQuoteVolume) {

		boolean available() {
			return quoteVolume != null && tradeCount != null && takerBuyBaseVolume != null && takerBuyQuoteVolume != null;
		}

		static OrderflowSnapshot fromCandle(Candle candle) {
			if (candle == null) {
				return null;
			}
			return new OrderflowSnapshot(
					readDoubleAccessor(candle, "quoteVolume"),
					readLongAccessor(candle, "tradeCount"),
					readDoubleAccessor(candle, "takerBuyBaseVolume"),
					readDoubleAccessor(candle, "takerBuyQuoteVolume"));
		}
	}

	private record LiquiditySnapshot(double bestBidPrice, double bestBidQty, double bestAskPrice, double bestAskQty,
			long eventTimeMs) {

		static LiquiditySnapshot fromContext(ApplicationContext context, String symbol) {
			if (context == null || symbol == null || symbol.isBlank()) {
				return null;
			}
			try {
				Class<?> watcherClass = Class.forName("com.binance.strategy.BookTickerStreamWatcher");
				Object bean = context.getBean(watcherClass);
				Method getSnapshot = watcherClass.getMethod("getSnapshot", String.class);
				Object snapshot = getSnapshot.invoke(bean, symbol);
				if (snapshot == null) {
					return null;
				}
				Double bid = readDoubleAccessor(snapshot, "bestBidPrice");
				Double bidQty = readDoubleAccessor(snapshot, "bestBidQty");
				Double ask = readDoubleAccessor(snapshot, "bestAskPrice");
				Double askQty = readDoubleAccessor(snapshot, "bestAskQty");
				Long eventTime = readLongAccessor(snapshot, "eventTimeMs");
				if (bid == null || bidQty == null || ask == null || askQty == null || eventTime == null) {
					return null;
				}
				return new LiquiditySnapshot(bid, bidQty, ask, askQty, eventTime);
			} catch (ReflectiveOperationException | RuntimeException ex) {
				LOGGER.debug("BookTicker reflection lookup unavailable: {}", ex.getMessage());
				return null;
			}
		}
	}

	private static Double readDoubleAccessor(Object target, String accessorName) {
		if (target == null) {
			return null;
		}
		try {
			Method method = target.getClass().getMethod(accessorName);
			Object value = method.invoke(target);
			if (value == null) {
				return null;
			}
			return value instanceof Number number ? number.doubleValue() : null;
		} catch (ReflectiveOperationException ex) {
			return null;
		}
	}

	private static Long readLongAccessor(Object target, String accessorName) {
		if (target == null) {
			return null;
		}
		try {
			Method method = target.getClass().getMethod(accessorName);
			Object value = method.invoke(target);
			if (value == null) {
				return null;
			}
			return value instanceof Number number ? number.longValue() : null;
		} catch (ReflectiveOperationException ex) {
			return null;
		}
	}

	private record LongSetupEval(boolean signal,
			String blockReason,
			List<String> failReasons,
			double ret1m,
			double range1m,
			double closePos1m,
			double lowerWickRatio,
			double bbWidth5m,
			double range5m,
			double high1h,
			double fromHigh1h,
			double btcRet1m,
			double entryPrice,
			double tpPrice,
			double slPrice) {
		private static LongSetupEval empty() {
			return new LongSetupEval(false, null, List.of(), Double.NaN, Double.NaN, Double.NaN, Double.NaN,
					Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN);
		}
	}

	private record ShortSetupEval(boolean pass,
			List<String> failReasons,
			double coinRet1,
			double btcRet1,
			double btcClosePos1,
			double entryPrice,
			double tpPrice,
			double slPrice) {
	}

	private static final class SymbolState {
		private final String symbol;
		private long seen1mCloses;
		private long seen5mCloses;
		private long lastEvaluated5mCloseTimeMs = -1L;
		private boolean baselinesReady;
		private boolean warmupDoneLogged;
		private long nextWarmupProgressLogAt1m = 10;
		private LocalDate dayKey;
		private int entriesToday;
		private Side positionSide = Side.NONE;
		private long entryTimeMs;
		private double entryPrice;
		private double qty;
		private double tpPrice;
		private double slPrice;
		private Long tpHitTimeMs;
		private Long slHitTimeMs;
		private HitSnapshot tpHitBar1m;
		private HitSnapshot slHitBar1m;
		private String bracketId;
		private Long entryOrderId;
		private String entryClientOrderId;
		private Long slOrderId;
		private String slClientOrderId;
		private Long tpOrderId;
		private String tpClientOrderId;
		private Double prevEma20_5m;
		private final Deque<Candle> last1m = new ArrayDeque<>();
		private final Deque<Candle> last5m = new ArrayDeque<>();
		private final BucketedFiveMinuteAggregator aggregator = new BucketedFiveMinuteAggregator();
		private final IndicatorState indicators = new IndicatorState();
		private final RegimeState regimeState = new RegimeState();

		private SymbolState(String symbol) {
			this.symbol = symbol;
		}
	}

	static final class BucketTransition {
		private final Candle completedCandle;
		private final Long incompleteBucketStartMs;
		private final int incompleteCount;

		private BucketTransition(Candle completedCandle, Long incompleteBucketStartMs, int incompleteCount) {
			this.completedCandle = completedCandle;
			this.incompleteBucketStartMs = incompleteBucketStartMs;
			this.incompleteCount = incompleteCount;
		}

		Candle completedCandle() {
			return completedCandle;
		}

		Long incompleteBucketStartMs() {
			return incompleteBucketStartMs;
		}

		int incompleteCount() {
			return incompleteCount;
		}
	}

	static final class BucketedFiveMinuteAggregator {
		private Long currentBucketStartMs;
		private final List<Candle> currentBucketCandles = new ArrayList<>(5);

		private BucketTransition addFinalOneMinute(Candle candle1m) {
			long openTimeMs = inferOpenTimeMsFromClose(candle1m.closeTime());
			long candleBucketStartMs = bucketStartMs(openTimeMs);
			if (currentBucketStartMs == null) {
				currentBucketStartMs = candleBucketStartMs;
				currentBucketCandles.add(candle1m);
				return new BucketTransition(null, null, 0);
			}
			if (candleBucketStartMs < currentBucketStartMs) {
				return new BucketTransition(null, null, 0);
			}
			if (candleBucketStartMs == currentBucketStartMs) {
				currentBucketCandles.add(candle1m);
				return new BucketTransition(null, null, 0);
			}

			BucketTransition finalized = finalizeCurrentBucket();
			currentBucketStartMs = candleBucketStartMs;
			currentBucketCandles.clear();
			currentBucketCandles.add(candle1m);
			return finalized;
		}

		private BucketTransition finalizeCurrentBucket() {
			if (currentBucketStartMs == null) {
				return new BucketTransition(null, null, 0);
			}
			if (currentBucketCandles.size() != 5) {
				return new BucketTransition(null, currentBucketStartMs, currentBucketCandles.size());
			}
			Candle first = currentBucketCandles.get(0);
			Candle last = currentBucketCandles.get(4);
			double high = currentBucketCandles.stream().mapToDouble(Candle::high).max().orElse(first.high());
			double low = currentBucketCandles.stream().mapToDouble(Candle::low).min().orElse(first.low());
			double volume = currentBucketCandles.stream().mapToDouble(Candle::volume).sum();
			Double quoteVolume = sumNullableDouble(currentBucketCandles, Candle::quoteVolume);
			Long tradeCount = sumNullableLong(currentBucketCandles, Candle::tradeCount);
			Double takerBuyBaseVolume = sumNullableDouble(currentBucketCandles, Candle::takerBuyBaseVolume);
			Double takerBuyQuoteVolume = sumNullableDouble(currentBucketCandles, Candle::takerBuyQuoteVolume);
			Candle candle5m = new Candle(
					first.open(),
					high,
					low,
					last.close(),
					volume,
					bucketEndMs(currentBucketStartMs),
					quoteVolume,
					tradeCount,
					takerBuyBaseVolume,
					takerBuyQuoteVolume);
			return new BucketTransition(candle5m, null, 0);
		}

		private BucketTransition flush() {
			return finalizeCurrentBucket();
		}
	}

	private static Double sumNullableDouble(List<Candle> candles, java.util.function.Function<Candle, Double> getter) {
		double sum = 0.0;
		for (Candle candle : candles) {
			Double value = getter.apply(candle);
			if (value == null) {
				return null;
			}
			sum += value;
		}
		return sum;
	}

	private static Long sumNullableLong(List<Candle> candles, java.util.function.Function<Candle, Long> getter) {
		long sum = 0L;
		for (Candle candle : candles) {
			Long value = getter.apply(candle);
			if (value == null) {
				return null;
			}
			sum += value;
		}
		return sum;
	}

	private static final class RegimeState {
		private RegimeTag rawRegimeTag = RegimeTag.CHOP;
		private RegimeTag activeRegimeTag = RegimeTag.CHOP;
		private int debounceCounter;
		private RegimeTag pendingRegime;

		// FIX: cooldownCounter kaldırıldı — TREND→CHOP geçişini geciktirerek stale TREND etiketiyle
		// kötü giriş yapılmasına neden oluyordu. Debounce zaten gereksiz titreşimi engelliyor.
		private RegimeTag update(RegimeTag raw, int debounceBars, int cooldownBars) {
			rawRegimeTag = raw;
			if (pendingRegime != raw) {
				pendingRegime = raw;
				debounceCounter = 1;
				return activeRegimeTag;
			}
			debounceCounter++;
			if (debounceCounter >= debounceBars && activeRegimeTag != raw) {
				activeRegimeTag = raw;
				debounceCounter = 0;
			}
			return activeRegimeTag;
		}
	}

	private static final class IndicatorState {
		private final Deque<Double> closeWindow20 = new ArrayDeque<>();
				private final Deque<Double> trWindow14 = new ArrayDeque<>();
		private double ema20;
		private double ema20Prev;
		private boolean ema20Ready;
		private double prevClose;
		private boolean prevCloseReady;
		private double prevCloseAtr;
		private boolean prevCloseAtrReady;
		private double avgGain;
		private double avgLoss;
		private int rsiCounter;
		private double ema12;
		private boolean ema12Ready;
		private double ema26;
		private boolean ema26Ready;
		private double macdSignal;
		private boolean macdSignalReady;
		private int bars;
		private final Ewma bwEma = new Ewma(20);
		private final Ewma atrEma = new Ewma(20);
		private final Ewma macdAbsEma = new Ewma(20);
		private final Ewma volEma = new Ewma(20);
		private final Ewma volRatioEma = new Ewma(20);
		private Metrics latest;

		private void update(Candle bar) {
			bars++;
			updateEma20(bar.close());
			updateRsi(bar.close());
			double atr14 = updateAtr(bar);
			double atrEmaValue = atrEma.update(atr14);
			double atrRatio = ratioOrNaN(atr14, atrEmaValue);
			double bbWidth = computeBbWidth(bar.close());
			double bwEmaValue = bwEma.update(bbWidth);
			double bwRatio = ratioOrNaN(bbWidth, bwEmaValue);

			updateMacd(bar.close());
			double macd = ema12 - ema26;
			double macdDelta = macd - macdSignal;
			double macdAbsEmaValue = macdAbsEma.update(Math.abs(macdDelta));
			double macdRatio = ratioOrNaN(Math.abs(macdDelta), macdAbsEmaValue);
			double ema20Prev = this.ema20Prev;

			double volEmaValue = volEma.update(bar.volume());
			double volRatio = ratioOrNaN(bar.volume(), volEmaValue);
			double volRatioOfEma = volRatioEma.initialized() ? ratioOrNaN(volRatio, volRatioEma.update(volRatio)) : 1.0;
			if (!volRatioEma.initialized()) {
				volRatioEma.update(volRatio);
			}

			double std = std(closeWindow20);
			double sma = average(closeWindow20);
			double bbUpper = sma + 2.0 * std;
			double bbLower = sma - 2.0 * std;
			double percentB = ratioOrNaN(bar.close() - bbLower, Math.max(bbUpper - bbLower, 1e-9));
			boolean bbOutside = bar.close() > bbUpper || bar.close() < bbLower;
			double ema20DistPct = ratio(Math.abs(bar.close() - ema20), Math.max(bar.close(), 1e-9));
			boolean ema20SlopeDown = ema20Ready && ema20 < ema20Prev;
			double rsi = computeRsi();

			latest = new Metrics(bbWidth, bwEmaValue, bwRatio, volRatio, volEmaValue, volRatioOfEma, macdRatio, atrRatio,
					ema20DistPct, percentB, rsi, bbLower, sma, bbUpper, bbOutside, bar.close(), ema20, ema20SlopeDown, macdDelta, macdAbsEmaValue, atr14, atrEmaValue, RegimeTag.CHOP, RegimeTag.CHOP);
		}

		private void setRegimes(RegimeTag raw, RegimeTag active) {
			if (latest != null) {
				latest = latest.withRegimes(raw, active);
			}
		}

		private Metrics metrics() {
			return latest;
		}

		private boolean baselineIndicatorsSeeded() {
			return bwEma.initialized() && atrEma.initialized() && macdAbsEma.initialized();
		}

		private double updateAtr(Candle bar) {
			double tr;
			if (!prevCloseAtrReady) {
				tr = bar.high() - bar.low();
				prevCloseAtrReady = true;
			} else {
				tr = Math.max(bar.high() - bar.low(),
						Math.max(Math.abs(bar.high() - prevCloseAtr), Math.abs(bar.low() - prevCloseAtr)));
			}
			prevCloseAtr = bar.close();
			push(trWindow14, tr, 14);
			return average(trWindow14);
		}

		private void updateEma20(double close) {
			if (!ema20Ready) {
				ema20 = close;
				ema20Prev = close;
				ema20Ready = true;
			} else {
				ema20Prev = ema20;
				ema20 = ema(ema20, close, 20);
			}
			push(closeWindow20, close, 20);
		}

		private void updateRsi(double close) {
			if (!prevCloseReady) {
				prevClose = close;
				prevCloseReady = true;
				return;
			}
			double change = close - prevClose;
			double gain = Math.max(change, 0.0);
			double loss = Math.max(-change, 0.0);
			rsiCounter++;
			if (rsiCounter == 1) {
				avgGain = gain;
				avgLoss = loss;
			} else {
				avgGain = (avgGain * 8 + gain) / 9;
				avgLoss = (avgLoss * 8 + loss) / 9;
			}
			prevClose = close;
		}

		private void updateMacd(double close) {
			if (!ema12Ready) {
				ema12 = close;
				ema12Ready = true;
			} else {
				ema12 = ema(ema12, close, 12);
			}
			if (!ema26Ready) {
				ema26 = close;
				ema26Ready = true;
			} else {
				ema26 = ema(ema26, close, 26);
			}
			double macd = ema12 - ema26;
			if (!macdSignalReady) {
				macdSignal = macd;
				macdSignalReady = true;
			} else {
				macdSignal = ema(macdSignal, macd, 9);
			}
		}

		private double computeBbWidth(double close) {
			double std = std(closeWindow20);
			double sma = average(closeWindow20);
			double upper = sma + 2.0 * std;
			double lower = sma - 2.0 * std;
			return ratio(upper - lower, Math.max(close, 1e-9));
		}

		private double computeRsi() {
			if (avgLoss <= 1e-9) {
				return 100.0;
			}
			double rs = avgGain / avgLoss;
			return 100.0 - (100.0 / (1.0 + rs));
		}

		private double ema(double prev, double current, int period) {
			double alpha = 2.0 / (period + 1.0);
			return alpha * current + (1.0 - alpha) * prev;
		}

		private double average(Deque<Double> values) {
			if (values.isEmpty()) {
				return 0.0;
			}
			return values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
		}

		private double std(Deque<Double> values) {
			if (values.size() < 2) {
				return 0.0;
			}
			double avg = average(values);
			double var = values.stream().mapToDouble(v -> (v - avg) * (v - avg)).average().orElse(0.0);
			return Math.sqrt(var);
		}

		private double ratio(double value, double base) {
			return base <= 1e-9 ? 1.0 : value / base;
		}

		private double ratioOrNaN(double value, double base) {
			return base <= 1e-9 ? Double.NaN : value / base;
		}

		private void push(Deque<Double> values, double value, int maxSize) {
			values.addLast(value);
			if (values.size() > maxSize) {
				values.removeFirst();
			}
		}

	}

	private record Metrics(
			double bbWidth_5m,
			double bwEma_5m,
			double bwRatio5m,
			double volRatio,
			double volEma_5m,
			double volRatioOfEma,
			double macdRatio5m,
			double atrRatio5m,
			double ema20DistPct,
			double bbPercentB_5m,
			double rsi9_5m,
			double bbLower,
			double bbMiddle,
			double bbUpper,
			boolean bbOutside_5m,
			double close5m,
			double ema20_5m,
			boolean ema20SlopeDown,
			double macdDelta,
			double macdAbsEma_5m,
			double atr14,
			double atrEma_5m,
			RegimeTag rawRegimeTag,
			RegimeTag activeRegimeTag) {

		private Metrics withRegimes(RegimeTag raw, RegimeTag active) {
			return new Metrics(bbWidth_5m, bwEma_5m, bwRatio5m, volRatio, volEma_5m, volRatioOfEma, macdRatio5m, atrRatio5m,
					ema20DistPct, bbPercentB_5m, rsi9_5m, bbLower, bbMiddle, bbUpper, bbOutside_5m,
					close5m, ema20_5m, ema20SlopeDown, macdDelta, macdAbsEma_5m, atr14, atrEma_5m, raw, active);
		}
	}

private static final class Ewma {
		private final double alpha;
		private boolean initialized;
		private double value;

		private Ewma(int period) {
			this.alpha = 2.0 / (period + 1.0);
		}

		private double update(double next) {
			if (!initialized) {
				value = next;
				initialized = true;
			} else {
				value = alpha * next + (1.0 - alpha) * value;
			}
			return value;
		}

		private boolean initialized() {
			return initialized;
		}
	}

	private static final class AsyncJsonlWriter {
		private final Logger logger = LoggerFactory.getLogger(AsyncJsonlWriter.class);
		private final ArrayBlockingQueue<LogItem> queue;
		private final AtomicBoolean running = new AtomicBoolean(false);
		private final AtomicLong dropped = new AtomicLong();
		private Thread worker;

		private AsyncJsonlWriter(int capacity) {
			this.queue = new ArrayBlockingQueue<>(capacity);
		}

		private void start() {
			if (!running.compareAndSet(false, true)) {
				return;
			}
			worker = new Thread(() -> {
				while (running.get() || !queue.isEmpty()) {
					try {
						LogItem item = queue.poll();
						if (item == null) {
							Thread.sleep(10);
							continue;
						}
						Files.createDirectories(item.path().getParent());
						Files.writeString(item.path(), item.line() + "\n", StandardCharsets.UTF_8,
								StandardOpenOption.CREATE, StandardOpenOption.APPEND);
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
					} catch (IOException ioe) {
						logger.warn("elite log write failed", ioe);
					}
				}
			});
			worker.setName("elite-v1-log-writer");
			worker.start();
		}

		private void write(Path path, String line, boolean critical) {
			LogItem item = new LogItem(path, line);
			if (critical) {
				try {
					queue.put(item);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
				return;
			}
			if (!queue.offer(item)) {
				long droppedCount = dropped.incrementAndGet();
				if (droppedCount % 100 == 0) {
					logger.warn("elite decision log dropped={} entries", droppedCount);
				}
			}
		}

		private void stop() {
			running.set(false);
			if (worker != null) {
				worker.interrupt();
			}
		}

		private record LogItem(Path path, String line) {
		}
	}
	static long bucketStartMs(long openTimeMs) {
		return (openTimeMs / FIVE_MIN_MS) * FIVE_MIN_MS;
	}

	static long bucketEndMs(long bucketStartMs) {
		return bucketStartMs + FIVE_MIN_MS - 1;
	}

	static long inferOpenTimeMsFromClose(long closeTimeMs) {
		return closeTimeMs - (ONE_MIN_MS - 1);
	}
}
