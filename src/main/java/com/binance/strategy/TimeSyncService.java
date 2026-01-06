package com.binance.strategy;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.binance.market.BinanceMarketClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import reactor.core.publisher.Mono;

@Component
public class TimeSyncService {

	private static final Logger LOGGER = LoggerFactory.getLogger(TimeSyncService.class);
	private static final long FIVE_MIN_MS = 300_000L;
	private static final long REST_REFRESH_MS = 60_000L;

	private final BinanceMarketClient marketClient;
	private final ObjectMapper objectMapper;
	private final Map<String, SyncState> states = new ConcurrentHashMap<>();

	public TimeSyncService(BinanceMarketClient marketClient, ObjectMapper objectMapper) {
		this.marketClient = marketClient;
		this.objectMapper = objectMapper;
	}

	public Optional<Candle> recordClosedOneMinute(String symbol, Candle candle) {
		SyncState state = states.computeIfAbsent(symbol, ignored -> new SyncState());
		synchronized (state) {
			if (candle.closeTime() <= state.lastClosed1mCloseTime) {
				return Optional.empty();
			}
			state.lastClosed1mCloseTime = candle.closeTime();
			Optional<Candle> applied = applyPendingFiveMinute(state, candle.closeTime());
			maybeRefreshFromRest(symbol, state, candle.closeTime());
			return applied;
		}
	}

	public void recordClosedFiveMinute(String symbol, Candle candle) {
		SyncState state = states.computeIfAbsent(symbol, ignored -> new SyncState());
		synchronized (state) {
			if (state.lastClosed5m != null && candle.closeTime() <= state.lastClosed5m.closeTime()) {
				return;
			}
			if (state.pendingClosed5m != null && candle.closeTime() <= state.pendingClosed5m.closeTime()) {
				return;
			}
			state.pendingClosed5m = candle;
		}
	}

	private Optional<Candle> applyPendingFiveMinute(SyncState state, long oneMinuteCloseTime) {
		if (state.pendingClosed5m == null) {
			return Optional.empty();
		}
		if (state.pendingClosed5m.closeTime() > oneMinuteCloseTime) {
			return Optional.empty();
		}
		Candle applied = state.pendingClosed5m;
		state.pendingClosed5m = null;
		if (state.lastClosed5m == null || applied.closeTime() > state.lastClosed5m.closeTime()) {
			state.lastClosed5m = applied;
		}
		return Optional.of(applied);
	}

	private void maybeRefreshFromRest(String symbol, SyncState state, long oneMinuteCloseTime) {
		long now = System.currentTimeMillis();
		if (now - state.lastRestFetchAtMs < REST_REFRESH_MS) {
			return;
		}
		if (state.pendingClosed5m != null) {
			return;
		}
		boolean stale = state.lastClosed5m == null
				|| oneMinuteCloseTime - state.lastClosed5m.closeTime() >= FIVE_MIN_MS * 2;
		if (!stale) {
			return;
		}
		state.lastRestFetchAtMs = now;
		marketClient.fetchFuturesKlinesRaw(symbol, "5m", 2)
				.flatMap(response -> {
					if (response.statusCode() != 200) {
						return Mono.empty();
					}
					return Mono.justOrEmpty(parseLastClosedFiveMinute(response.body(), symbol));
				})
				.doOnNext(candle -> applyRestClosedFiveMinute(symbol, candle))
				.doOnError(error -> LOGGER.warn("EVENT=TIME_SYNC_REST_FAILED symbol={} error={}", symbol,
						error.getMessage()))
				.onErrorResume(error -> Mono.empty())
				.subscribe();
	}

	private Optional<Candle> parseLastClosedFiveMinute(String json, String symbol) {
		try {
			JsonNode root = objectMapper.readTree(json);
			if (root == null || !root.isArray() || root.size() < 2) {
				return Optional.empty();
			}
			JsonNode entry = root.get(root.size() - 2);
			if (entry == null || !entry.isArray() || entry.size() < 7) {
				return Optional.empty();
			}
			double open = entry.get(1).asDouble();
			double high = entry.get(2).asDouble();
			double low = entry.get(3).asDouble();
			double close = entry.get(4).asDouble();
			double volume = entry.get(5).asDouble();
			long closeTime = entry.get(6).asLong();
			return Optional.of(new Candle(open, high, low, close, volume, closeTime));
		} catch (Exception ex) {
			LOGGER.warn("EVENT=TIME_SYNC_REST_PARSE_FAILED symbol={} error={}", symbol, ex.getMessage());
			return Optional.empty();
		}
	}

	private void applyRestClosedFiveMinute(String symbol, Candle candle) {
		SyncState state = states.computeIfAbsent(symbol, ignored -> new SyncState());
		synchronized (state) {
			if (state.lastClosed5m != null && candle.closeTime() <= state.lastClosed5m.closeTime()) {
				return;
			}
			if (state.pendingClosed5m != null && candle.closeTime() <= state.pendingClosed5m.closeTime()) {
				return;
			}
			state.pendingClosed5m = candle;
		}
	}

	private static final class SyncState {
		private long lastClosed1mCloseTime;
		private Candle lastClosed5m;
		private Candle pendingClosed5m;
		private long lastRestFetchAtMs;
	}
}
