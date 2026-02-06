package com.binance.exchange.strategy.elite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.binance.strategy.Candle;

class EliteV1StrategyTest {

	@Test
	void longTpTouchShouldExitTakeProfit() {
		double tp = EliteV1Strategy.roundUp(100.0 * 1.004, 0.01);
		Candle bar = new Candle(100.0, 100.41, 100.0, 100.2, 10.0, 1_000L);
		EliteV1Strategy.ExitReason reason = EliteV1Strategy.resolveTouchExit(
				EliteV1Strategy.Side.LONG,
				tp,
				99.80,
				bar,
				EliteV1Properties.ConflictResolution.SL_FIRST);
		assertEquals(EliteV1Strategy.ExitReason.TAKE_PROFIT, reason);
	}

	@Test
	void longSlTouchShouldExitStopLoss() {
		Candle bar = new Candle(100.0, 100.1, 99.79, 99.9, 10.0, 1_000L);
		EliteV1Strategy.ExitReason reason = EliteV1Strategy.resolveTouchExit(
				EliteV1Strategy.Side.LONG,
				100.40,
				99.80,
				bar,
				EliteV1Properties.ConflictResolution.SL_FIRST);
		assertEquals(EliteV1Strategy.ExitReason.STOP_LOSS, reason);
	}

	@Test
	void conflictShouldResolveBySlFirst() {
		Candle bar = new Candle(100.0, 100.5, 99.7, 100.0, 10.0, 1_000L);
		EliteV1Strategy.ExitReason reason = EliteV1Strategy.resolveTouchExit(
				EliteV1Strategy.Side.LONG,
				100.40,
				99.80,
				bar,
				EliteV1Properties.ConflictResolution.SL_FIRST);
		assertEquals(EliteV1Strategy.ExitReason.STOP_LOSS, reason);
	}

	@Test
	void tickRoundingShouldFollowDirectionRules() {
		double longSl = EliteV1Strategy.roundDown(99.801, 0.01);
		double shortSl = EliteV1Strategy.roundUp(100.199, 0.01);
		assertEquals(99.80, longSl);
		assertEquals(100.20, shortSl);
	}

	@Test
	void timeStopShouldTriggerAtTwentyMinutes() {
		long entry = 1_000L;
		long now = entry + 20L * 60_000L;
		assertTrue(EliteV1Strategy.shouldTimeStop(entry, now, 20));
	}

	@Test
	void warmupBelowThresholdShouldReturnInputsNotReady() {
		EliteV1Strategy.PreCheckAction action = EliteV1Strategy.evaluatePreChecks(
				false,
				EliteV1Strategy.Side.NONE,
				false,
				0,
				1);
		assertEquals(EliteV1Strategy.DecisionAction.INPUTS_NOT_READY, action.action());
	}

	@Test
	void tradedTodayShouldBlockNewEntry() {
		EliteV1Strategy.PreCheckAction action = EliteV1Strategy.evaluatePreChecks(
				true,
				EliteV1Strategy.Side.NONE,
				true,
				0,
				1);
		assertEquals(EliteV1Strategy.DecisionAction.TRADDED_TODAY, action.action());
		assertEquals("TRADDED_TODAY", action.blockReason());
	}

	@Test
	void shortEliteMomentumAllMatchShouldPass() {
		var cfg = momentumCfg();
		List<String> fails = EliteV1Strategy.evaluateShortMomentumFailures(
				0.40,
				1.60,
				1.40,
				99.0,
				100.0,
				true,
				-0.1,
				2.60,
				cfg);
		assertTrue(fails.isEmpty());
	}

	@Test
	void shortEliteMomentumCloseAboveEmaShouldFailCloseBelowEma20() {
		var cfg = momentumCfg();
		List<String> fails = EliteV1Strategy.evaluateShortMomentumFailures(
				0.40,
				1.60,
				1.40,
				100.0,
				100.0,
				true,
				-0.1,
				2.60,
				cfg);
		assertTrue(fails.contains("CLOSE_BELOW_EMA20"));
	}

	@Test
	void shortEliteMomentumEma20SlopeUpShouldFailSlope() {
		var cfg = momentumCfg();
		List<String> fails = EliteV1Strategy.evaluateShortMomentumFailures(
				0.40,
				1.60,
				1.40,
				99.0,
				100.0,
				false,
				-0.1,
				2.60,
				cfg);
		assertTrue(fails.contains("EMA20_SLOPE"));
	}

	@Test
	void shortEliteMomentumMissingVolRatioShouldFailVol() {
		var cfg = momentumCfg();
		double missingVolRatioOfEma = 1.0;
		List<String> fails = EliteV1Strategy.evaluateShortMomentumFailures(
				0.40,
				1.60,
				missingVolRatioOfEma,
				99.0,
				100.0,
				true,
				-0.1,
				2.60,
				cfg);
		assertFalse(fails.isEmpty());
		assertTrue(fails.contains("VOL"));
	}

	@Test
	void bucketAlignmentShouldUseExchangeFiveMinuteBoundary() {
		long openMs = 1_700_000_000_000L;
		long bucketStart = EliteV1Strategy.bucketStartMs(openMs);
		long bucketEnd = EliteV1Strategy.bucketEndMs(bucketStart);
		var utc = java.time.Instant.ofEpochMilli(bucketEnd).atZone(java.time.ZoneOffset.UTC);
		var tr = java.time.Instant.ofEpochMilli(bucketEnd).atZone(java.time.ZoneId.of("Europe/Istanbul"));
		assertEquals(59, utc.getSecond());
		assertEquals(999_000_000, utc.getNano());
		assertEquals(59, tr.getSecond());
		assertEquals(999_000_000, tr.getNano());
		assertTrue(java.util.Set.of(4, 9, 14, 19, 24, 29, 34, 39, 44, 49, 54, 59).contains(utc.getMinute()));
		assertTrue(java.util.Set.of(4, 9, 14, 19, 24, 29, 34, 39, 44, 49, 54, 59).contains(tr.getMinute()));
	}

	@Test
	void incompleteBucketShouldNotFinalizeFiveMinuteCandle() {
		EliteV1Strategy.BucketedFiveMinuteAggregator agg = new EliteV1Strategy.BucketedFiveMinuteAggregator();
		long baseClose = 1_700_000_099_999L;
		agg.addFinalOneMinute(new Candle(1, 1, 1, 1, 1, baseClose));
		agg.addFinalOneMinute(new Candle(1, 1, 1, 1, 1, baseClose + 60_000L));
		agg.addFinalOneMinute(new Candle(1, 1, 1, 1, 1, baseClose + 120_000L));
		agg.addFinalOneMinute(new Candle(1, 1, 1, 1, 1, baseClose + 180_000L));
		EliteV1Strategy.BucketTransition transition = agg.addFinalOneMinute(new Candle(1, 1, 1, 1, 1, baseClose + 300_000L));
		assertEquals(null, transition.completedCandle());
		assertTrue(transition.incompleteBucketStartMs() != null);
		assertEquals(4, transition.incompleteCount());
	}

	private EliteV1Properties.ShortEliteMomentum momentumCfg() {
		return new EliteV1Properties.ShortEliteMomentum(
				0.35,
				0.60,
				1.40,
				2.80,
				1.10,
				2.50,
				2.50,
				true,
				true,
				true);
	}
}
