package com.binance.exchange.strategy.elite;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
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
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;

import com.binance.config.BinanceProperties;
import com.binance.market.dto.KlineEvent;
import com.binance.strategy.Candle;
import com.binance.strategy.Strategy;
import com.binance.strategy.StrategyType;
import com.binance.strategy.SymbolFilterService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import jakarta.annotation.PreDestroy;
import reactor.core.Disposable;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;

@Component
public class EliteV1Strategy implements Strategy {

	private static final Logger LOGGER = LoggerFactory.getLogger(EliteV1Strategy.class);
	private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.BASIC_ISO_DATE;
	private static final double DEFAULT_TICK_SIZE = 0.01;
	private static final Path DECISION_DIR = Paths.get("signals", "decisions");
	private static final Path TRADE_DIR = Paths.get("signals", "trades");

	private final BinanceProperties binanceProperties;
	private final EliteV1Properties props;
	private final ObjectMapper objectMapper;
	private final SymbolFilterService symbolFilterService;
	private final ReactorNettyWebSocketClient webSocketClient = new ReactorNettyWebSocketClient();
	private final Scheduler loop = Schedulers.newSingle("elite-v1-loop", true);
	private final Map<String, SymbolState> states = new ConcurrentHashMap<>();
	private final AtomicBoolean started = new AtomicBoolean(false);
	private final AsyncJsonlWriter writer = new AsyncJsonlWriter(20_000);
	private final AtomicInteger globalOpenPositions = new AtomicInteger(0);
	private volatile Disposable wsSubscription;
	private ZoneId zoneId;

	public EliteV1Strategy(BinanceProperties binanceProperties,
			EliteV1Properties props,
			ObjectMapper objectMapper,
			SymbolFilterService symbolFilterService) {
		this.binanceProperties = binanceProperties;
		this.props = props;
		this.objectMapper = objectMapper;
		this.symbolFilterService = symbolFilterService;
	}

	@Override
	public StrategyType type() {
		return StrategyType.ELITE_V1;
	}

	@Override
	public void start() {
		if (!started.compareAndSet(false, true)) {
			return;
		}
		validateConfig();
		if (props.mode() == EliteV1Properties.Mode.LIVE) {
			LOGGER.warn("ELITE_V1 LIVE mode not implemented; falling back to PAPER behavior.");
		}
		zoneId = ZoneId.of(props.zoneId());
		writer.start();
		props.symbols().forEach(symbol -> states.put(symbol, new SymbolState(symbol)));
		symbolFilterService.preloadFilters(props.symbols()).subscribe();

		String streams = props.symbols().stream()
				.map(symbol -> symbol.toLowerCase() + "@kline_1m")
				.collect(Collectors.joining("/"));
		String base = binanceProperties.useTestnet()
				? "wss://stream.binancefuture.com/stream?streams="
				: "wss://fstream.binance.com/stream?streams=";
		URI uri = URI.create(base + streams);

		wsSubscription = webSocketClient.execute(uri, session -> session.receive()
				.map(message -> message.getPayloadAsText())
				.publishOn(loop)
				.doOnNext(this::onWsMessage)
				.then())
				.retryWhen(Retry.backoff(Long.MAX_VALUE, java.time.Duration.ofSeconds(1)))
				.subscribe();
		LOGGER.info("ELITE_V1 started mode={} symbols={} zone={}", props.mode(), props.symbols().size(), props.zoneId());
	}

	@Override
	public void stop() {
		if (!started.compareAndSet(true, false)) {
			return;
		}
		if (wsSubscription != null) {
			wsSubscription.dispose();
		}
		writer.stop();
		loop.dispose();
	}

	@PreDestroy
	public void onDestroy() {
		stop();
	}

	private void onWsMessage(String payload) {
		try {
			JsonNode root = objectMapper.readTree(payload);
			JsonNode data = root.path("data");
			KlineEvent event = objectMapper.treeToValue(data.isMissingNode() ? root : data, KlineEvent.class);
			if (event == null || event.kline() == null || !event.kline().closed()) {
				return;
			}
			SymbolState state = states.get(event.symbol());
			if (state == null) {
				return;
			}
			Candle candle = new Candle(event.kline().open(),
					event.kline().high(),
					event.kline().low(),
					event.kline().close(),
					event.kline().volume(),
					event.kline().closeTime());
			onClosed1m(state, candle);
		} catch (Exception ex) {
			LOGGER.warn("ELITE_V1 parse error", ex);
		}
	}

	private void onClosed1m(SymbolState state, Candle bar1m) {
		state.last1m.addLast(bar1m);
		if (state.last1m.size() > 300) {
			state.last1m.removeFirst();
		}
		checkPaperExit(state, bar1m);

		Candle bar5m = state.aggregator.addFinalOneMinute(bar1m);
		if (bar5m == null) {
			return;
		}
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

	private void evaluateAt5m(SymbolState state, Candle bar5m) {
		rollDay(state, bar5m.closeTime());
		Metrics metrics = state.indicators.metrics();
		if (metrics == null) {
			writeDecision(state, bar5m, "INPUTS_NOT_READY", null, "INPUTS_NOT_READY", null);
			return;
		}

		PreCheckAction preCheck = evaluatePreChecks(state.indicators.baselinesReady(props.warmupMin5mBars()),
				state.positionSide,
				state.entriesToday >= props.maxEntriesPerSymbolPerDay(),
				globalOpenPositions.get(),
				props.maxOpenPositionsGlobal());
		if (preCheck.action != DecisionAction.CONTINUE) {
			writeDecision(state, bar5m, preCheck.action.name(), null, preCheck.blockReason, metrics);
			return;
		}

		Candidate longCandidate = evaluateLong(metrics);
		Candidate shortCandidate = evaluateShort(metrics);

		if (longCandidate.enter && shortCandidate.enter) {
			writeDecision(state, bar5m, "NO_ENTRY", null, "BOTH_SIDES_MATCHED", metrics);
			return;
		}
		if (longCandidate.enter) {
			openPaperPosition(state, bar5m, Side.LONG, longCandidate.setup, metrics.activeRegimeTag);
			writeDecision(state, bar5m, "ENTER_LONG", longCandidate.setup, null, metrics);
			return;
		}
		if (shortCandidate.enter) {
			openPaperPosition(state, bar5m, Side.SHORT, shortCandidate.setup, metrics.activeRegimeTag);
			writeDecision(state, bar5m, "ENTER_SHORT", shortCandidate.setup, null, metrics);
			return;
		}
		String blockReason = shortCandidate.blockReason != null ? shortCandidate.blockReason : longCandidate.blockReason;
		writeDecision(state, bar5m, "NO_ENTRY", null, blockReason, metrics);
	}

	private void rollDay(SymbolState state, long closeTimeMs) {
		LocalDate day = Instant.ofEpochMilli(closeTimeMs).atZone(zoneId).toLocalDate();
		if (!Objects.equals(day, state.dayKey)) {
			state.dayKey = day;
			state.entriesToday = 0;
		}
	}

	static PreCheckAction evaluatePreChecks(boolean baselinesReady,
			Side positionSide,
			boolean tradedToday,
			int globalOpenPositions,
			int maxOpenPositionsGlobal) {
		if (!baselinesReady) {
			return new PreCheckAction(DecisionAction.INPUTS_NOT_READY, null);
		}
		if (positionSide != Side.NONE) {
			return new PreCheckAction(DecisionAction.IN_POSITION, null);
		}
		if (tradedToday) {
			return new PreCheckAction(DecisionAction.TRADDED_TODAY, "TRADDED_TODAY");
		}
		if (globalOpenPositions >= maxOpenPositionsGlobal) {
			return new PreCheckAction(DecisionAction.GLOBAL_MAX_OPEN_POS, "GLOBAL_MAX_OPEN_POS");
		}
		return new PreCheckAction(DecisionAction.CONTINUE, null);
	}

	private Candidate evaluateLong(Metrics m) {
		if (!props.longConfig().enabled() || m.activeRegimeTag != RegimeTag.CHOP) {
			return Candidate.noEntry(null);
		}
		boolean match = m.rsi9_5m >= props.longConfig().rsiMin()
				&& m.rsi9_5m <= props.longConfig().rsiMax()
				&& m.ema20DistPct >= props.longConfig().ema20DistMin()
				&& m.bbPercentB_5m <= props.longConfig().bbPercentBMax();
		if (!match) {
			return Candidate.noEntry(null);
		}
		if (props.longConfig().enableSetup5SafetyGate()) {
			if (m.bbWidth_5m >= props.longConfig().setup5().maxBbWidth()) {
				return Candidate.noEntry("SETUP5_BLOCK_BBWIDTH_TOO_WIDE");
			}
			if (m.volRatio >= props.longConfig().setup5().maxVolRatio()) {
				return Candidate.noEntry("SETUP5_BLOCK_VOL_SPIKE");
			}
			if (m.bwRatio5m > props.longConfig().setup5().chopMaxBwRatio()) {
				return Candidate.noEntry("SETUP5_BLOCK_CHOP_BWRATIO");
			}
		}
		return Candidate.enter("SETUP5_ELITE");
	}

	private Candidate evaluateShort(Metrics m) {
		if (!props.shortConfig().enabled() || m.activeRegimeTag != RegimeTag.TREND) {
			return Candidate.noEntry(null);
		}
		if (m.bbOutside_5m && props.shortConfig().veto().requireBbOutsideFalse()) {
			return Candidate.noEntry("SHORT_VETO_BB_OUTSIDE");
		}
		if (m.bbPercentB_5m <= props.shortConfig().veto().bbPercentBMinExclusive()) {
			return Candidate.noEntry("SHORT_VETO_PB_TOO_LOW");
		}
		if (m.ema20DistPct > props.shortConfig().veto().ema20DistPctMax()) {
			return Candidate.noEntry("SHORT_VETO_EMA20_CHASE");
		}
		if (matchShortBand(m, props.shortConfig().elite1())) {
			return Candidate.enter("SHORT_ELITE_1");
		}
		if (matchShortBand(m, props.shortConfig().elite2())) {
			return Candidate.enter("SHORT_ELITE_2");
		}
		return Candidate.noEntry("NO_SHORT_ELITE_MATCH");
	}

	private boolean matchShortBand(Metrics m, EliteV1Properties.ShortEliteBand band) {
		return m.bbPercentB_5m >= band.pbMin()
				&& m.bbPercentB_5m < band.pbMax()
				&& m.bwRatio5m >= band.bwRatioMin()
				&& m.bwRatio5m < band.bwRatioMax()
				&& m.volRatioOfEma <= band.volRatioOfEmaMax()
				&& m.macdRatio5m >= band.macdRatioMin();
	}

	private void openPaperPosition(SymbolState state,
			Candle bar5m,
			Side side,
			String matchedSetup,
			RegimeTag activeRegimeTag) {
		double entryPrice = bar5m.close();
		double qty = props.paperNotionalUsd() / Math.max(entryPrice, 1e-9);
		double tickSize = resolveTickSize(state.symbol);

		double tpRaw;
		double slRaw;
		double tpPrice;
		double slPrice;
		if (side == Side.LONG) {
			tpRaw = entryPrice * (1.0 + props.tpPct());
			slRaw = entryPrice * (1.0 - props.slPct());
			tpPrice = roundUp(tpRaw, tickSize);
			slPrice = roundDown(slRaw, tickSize);
		} else {
			tpRaw = entryPrice * (1.0 - props.tpPct());
			slRaw = entryPrice * (1.0 + props.slPct());
			tpPrice = roundDown(tpRaw, tickSize);
			slPrice = roundUp(slRaw, tickSize);
		}

		state.positionSide = side;
		state.entryPrice = entryPrice;
		state.qty = qty;
		state.entryTimeMs = bar5m.closeTime();
		state.tpPrice = tpPrice;
		state.slPrice = slPrice;
		state.bracketId = UUID.randomUUID().toString();
		state.entriesToday++;
		globalOpenPositions.incrementAndGet();

		ObjectNode node = objectMapper.createObjectNode();
		node.put("type", "ENTRY");
		node.put("symbol", state.symbol);
		node.put("time", Instant.ofEpochMilli(bar5m.closeTime()).toString());
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
		writer.write(tradePath(state.symbol, state.dayKey), node.toString(), true);
	}

	private void checkPaperExit(SymbolState state, Candle oneMinuteBar) {
		if (state.positionSide == Side.NONE || state.bracketId == null) {
			return;
		}
		ExitReason touchReason = resolveTouchExit(state.positionSide,
				state.tpPrice,
				state.slPrice,
				oneMinuteBar,
				props.conflictResolution());
		if (touchReason != null) {
			double exitPrice = touchReason == ExitReason.TAKE_PROFIT ? state.tpPrice : state.slPrice;
			exitPosition(state, touchReason, exitPrice, oneMinuteBar.closeTime());
			return;
		}
		if (shouldTimeStop(state.entryTimeMs, oneMinuteBar.closeTime(), props.timeStopMinutes())) {
			exitPosition(state, ExitReason.TIME_STOP_20M, oneMinuteBar.close(), oneMinuteBar.closeTime());
		}
	}

	private void exitPosition(SymbolState state, ExitReason reason, double exitPrice, long exitTimeMs) {
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
		writer.write(tradePath(state.symbol, state.dayKey), node.toString(), true);

		state.positionSide = Side.NONE;
		state.bracketId = null;
		globalOpenPositions.updateAndGet(v -> Math.max(0, v - 1));
	}

	private void writeDecision(SymbolState state,
			Candle bar5m,
			String action,
			String matchedSetup,
			String blockReason,
			Metrics metrics) {
		ObjectNode node = objectMapper.createObjectNode();
		node.put("type", "DECISION");
		node.put("symbol", state.symbol);
		node.put("time", Instant.ofEpochMilli(bar5m.closeTime()).toString());
		node.put("dayKey", DAY_FMT.format(state.dayKey));
		node.put("entriesToday", state.entriesToday);
		node.put("baselinesReady", state.indicators.baselinesReady(props.warmupMin5mBars()));
		if (metrics != null) {
			node.put("rawRegimeTag", metrics.rawRegimeTag.name());
			node.put("activeRegimeTag", metrics.activeRegimeTag.name());
			ObjectNode metricNode = node.putObject("metrics");
			metricNode.put("bbWidth", metrics.bbWidth_5m);
			metricNode.put("bwRatio", metrics.bwRatio5m);
			metricNode.put("volRatio", metrics.volRatio);
			metricNode.put("ema20DistPct", metrics.ema20DistPct);
			metricNode.put("percentB", metrics.bbPercentB_5m);
			metricNode.put("macdRatio", metrics.macdRatio5m);
			metricNode.put("atrRatio", metrics.atrRatio5m);
		}
		node.put("action", action);
		node.put("matchedSetup", matchedSetup);
		node.put("blockReason", blockReason);
		writer.write(decisionPath(state.symbol, state.dayKey), node.toString(), false);
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

	static boolean shouldTimeStop(long entryTimeMs, long nowMs, int timeStopMinutes) {
		return nowMs - entryTimeMs >= (long) timeStopMinutes * 60_000L;
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

	static RegimeTag rawRegime(double bwRatio, double macdRatio, double chopBwRatioMax, double chopMacdRatioMax) {
		if (bwRatio < chopBwRatioMax && macdRatio < chopMacdRatioMax) {
			return RegimeTag.CHOP;
		}
		return RegimeTag.TREND;
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
		TIME_STOP_20M
	}

	enum DecisionAction {
		CONTINUE,
		INPUTS_NOT_READY,
		IN_POSITION,
		TRADDED_TODAY,
		GLOBAL_MAX_OPEN_POS
	}

	record PreCheckAction(DecisionAction action, String blockReason) {
	}

	private record Candidate(boolean enter, String setup, String blockReason) {
		private static Candidate enter(String setup) {
			return new Candidate(true, setup, null);
		}

		private static Candidate noEntry(String blockReason) {
			return new Candidate(false, null, blockReason);
		}
	}

	private static final class SymbolState {
		private final String symbol;
		private LocalDate dayKey;
		private int entriesToday;
		private Side positionSide = Side.NONE;
		private long entryTimeMs;
		private double entryPrice;
		private double qty;
		private double tpPrice;
		private double slPrice;
		private String bracketId;
		private final Deque<Candle> last1m = new ArrayDeque<>();
		private final Deque<Candle> last5m = new ArrayDeque<>();
		private final FiveBarAggregator aggregator = new FiveBarAggregator();
		private final IndicatorState indicators = new IndicatorState();
		private final RegimeState regimeState = new RegimeState();

		private SymbolState(String symbol) {
			this.symbol = symbol;
		}
	}

	private static final class FiveBarAggregator {
		private final List<Candle> buffer = new ArrayList<>(5);

		private Candle addFinalOneMinute(Candle candle1m) {
			buffer.add(candle1m);
			if (buffer.size() < 5) {
				return null;
			}
			Candle first = buffer.get(0);
			Candle last = buffer.get(4);
			double high = buffer.stream().mapToDouble(Candle::high).max().orElse(first.high());
			double low = buffer.stream().mapToDouble(Candle::low).min().orElse(first.low());
			double volume = buffer.stream().mapToDouble(Candle::volume).sum();
			Candle candle5m = new Candle(first.open(), high, low, last.close(), volume, last.closeTime());
			buffer.clear();
			return candle5m;
		}
	}

	private static final class RegimeState {
		private RegimeTag rawRegimeTag = RegimeTag.CHOP;
		private RegimeTag activeRegimeTag = RegimeTag.CHOP;
		private int debounceCounter;
		private int cooldownCounter;
		private RegimeTag pendingRegime;

		private RegimeTag update(RegimeTag raw, int debounceBars, int cooldownBars) {
			rawRegimeTag = raw;
			if (cooldownCounter > 0) {
				cooldownCounter--;
				return activeRegimeTag;
			}
			if (pendingRegime != raw) {
				pendingRegime = raw;
				debounceCounter = 1;
				return activeRegimeTag;
			}
			debounceCounter++;
			if (debounceCounter >= debounceBars && activeRegimeTag != raw) {
				activeRegimeTag = raw;
				cooldownCounter = cooldownBars;
				debounceCounter = 0;
			}
			return activeRegimeTag;
		}
	}

	private static final class IndicatorState {
		private final Deque<Double> closeWindow20 = new ArrayDeque<>();
		private final Deque<Double> volumeWindow20 = new ArrayDeque<>();
		private final Deque<Double> trWindow14 = new ArrayDeque<>();
		private double ema20;
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
			double atrRatio = ratio(atr14, atrEma.update(atr14));
			double bbWidth = computeBbWidth(bar.close());
			double bwRatio = ratio(bbWidth, bwEma.update(bbWidth));

			updateMacd(bar.close());
			double macd = ema12 - ema26;
			double macdDelta = macd - macdSignal;
			double macdRatio = ratio(Math.abs(macdDelta), macdAbsEma.update(Math.abs(macdDelta)));

			double volRatio = ratio(bar.volume(), volEma.update(bar.volume()));
			double volRatioOfEma = volRatioEma.initialized() ? ratio(volRatio, volRatioEma.update(volRatio)) : 1.0;
			if (!volRatioEma.initialized()) {
				volRatioEma.update(volRatio);
			}

			double std = std(closeWindow20);
			double sma = average(closeWindow20);
			double bbUpper = sma + 2.0 * std;
			double bbLower = sma - 2.0 * std;
			double percentB = ratio(bar.close() - bbLower, Math.max(bbUpper - bbLower, 1e-9));
			boolean bbOutside = bar.close() > bbUpper || bar.close() < bbLower;
			double ema20DistPct = ratio(Math.abs(bar.close() - ema20), Math.max(bar.close(), 1e-9));
			double rsi = computeRsi();

			latest = new Metrics(bbWidth, bwRatio, volRatio, volRatioOfEma, macdRatio, atrRatio,
					ema20DistPct, percentB, rsi, bbOutside, RegimeTag.CHOP, RegimeTag.CHOP);
		}

		private void setRegimes(RegimeTag raw, RegimeTag active) {
			if (latest != null) {
				latest = latest.withRegimes(raw, active);
			}
		}

		private Metrics metrics() {
			return latest;
		}

		private boolean baselinesReady(int warmupMin5mBars) {
			return bars >= warmupMin5mBars && bwEma.initialized() && atrEma.initialized() && macdAbsEma.initialized();
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
				ema20Ready = true;
			} else {
				ema20 = ema(ema20, close, 20);
			}
			push(closeWindow20, close, 20);
			push(volumeWindow20, close, 20);
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

		private void push(Deque<Double> values, double value, int maxSize) {
			values.addLast(value);
			if (values.size() > maxSize) {
				values.removeFirst();
			}
		}
	}

	private record Metrics(
			double bbWidth_5m,
			double bwRatio5m,
			double volRatio,
			double volRatioOfEma,
			double macdRatio5m,
			double atrRatio5m,
			double ema20DistPct,
			double bbPercentB_5m,
			double rsi9_5m,
			boolean bbOutside_5m,
			RegimeTag rawRegimeTag,
			RegimeTag activeRegimeTag) {

		private Metrics withRegimes(RegimeTag raw, RegimeTag active) {
			return new Metrics(bbWidth_5m, bwRatio5m, volRatio, volRatioOfEma, macdRatio5m, atrRatio5m,
					ema20DistPct, bbPercentB_5m, rsi9_5m, bbOutside_5m, raw, active);
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
}
