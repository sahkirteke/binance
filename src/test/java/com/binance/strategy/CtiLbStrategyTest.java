package com.binance.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.List;

import com.binance.exchange.BinanceFuturesOrderClient;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

class CtiLbStrategyTest {

	@Test
	void isFiniteBetweenIsInclusive() {
		assertThat(CtiLbStrategy.isFiniteBetween(0.0114, 0.0114, 0.0130)).isTrue();
		assertThat(CtiLbStrategy.isFiniteBetween(0.0130, 0.0114, 0.0130)).isTrue();
		assertThat(CtiLbStrategy.isFiniteBetween(0.0113, 0.0114, 0.0130)).isFalse();
		assertThat(CtiLbStrategy.isFiniteBetween(Double.NaN, 0.0114, 0.0130)).isFalse();
	}

	@Test
	void isFiniteAtLeastIsInclusive() {
		assertThat(CtiLbStrategy.isFiniteAtLeast(0.004, 0.004)).isTrue();
		assertThat(CtiLbStrategy.isFiniteAtLeast(0.0039, 0.004)).isFalse();
		assertThat(CtiLbStrategy.isFiniteAtLeast(Double.NaN, 0.004)).isFalse();
	}

	@Test
	void isFiniteGreaterThanIsExclusive() {
		assertThat(CtiLbStrategy.isFiniteGreaterThan(0.000051, 0.00005)).isTrue();
		assertThat(CtiLbStrategy.isFiniteGreaterThan(0.00005, 0.00005)).isFalse();
		assertThat(CtiLbStrategy.isFiniteGreaterThan(Double.NaN, 0.00005)).isFalse();
	}

	@Test
	void resolveEntryDecisionBlocksWhenLongMacdNotAqua() throws Exception {
		CtiLbStrategy strategy = newStrategy();
		CtiLbStrategy.Indicators indicators = new CtiLbStrategy.Indicators(
				0.0120,
				0.50,
				1.0,
				0.0009,
				40.0,
				18.0,
				0.01,
				MacdHistColor.BLUE);
		CtiLbStrategy.EntryDecision decision = invokeResolveEntryDecision(strategy, CtiLbStrategy.PositionState.NONE,
				CtiDirection.LONG, indicators);
		assertThat(decision.confirmedRec()).isNull();
		assertThat(decision.blockReason()).isEqualTo("LONG_MACD_COLOR_NOT_AQUA");
	}

	@Test
	void resolveEntryDecisionDoesNotAllowSetup3() throws Exception {
		CtiLbStrategy strategy = newStrategy();
		CtiLbStrategy.Indicators indicators = new CtiLbStrategy.Indicators(
				0.0105,
				0.40,
				0.8,
				0.0008,
				40.0,
				14.0,
				0.01,
				MacdHistColor.AQUA);
		CtiLbStrategy.EntryDecision decision = invokeResolveEntryDecision(strategy, CtiLbStrategy.PositionState.NONE,
				CtiDirection.LONG, indicators);
		assertThat(decision.confirmedRec()).isNull();
		assertThat(decision.blockReason()).isEqualTo("NO_LONG_SETUP_MATCHED");
	}

	@Test
	void resolveEntryDecisionBlocksWhenInPosition() throws Exception {
		CtiLbStrategy strategy = newStrategy();
		CtiLbStrategy.Indicators indicators = new CtiLbStrategy.Indicators(
				0.0116,
				0.50,
				0.9,
				0.0008,
				40.0,
				14.0,
				0.00001,
				null);
		CtiLbStrategy.EntryDecision decision = invokeResolveEntryDecision(strategy, CtiLbStrategy.PositionState.LONG,
				CtiDirection.LONG, indicators);
		assertThat(decision.confirmedRec()).isNull();
		assertThat(decision.blockReason()).isEqualTo("IN_POSITION_NO_ENTRY");
	}

	@Test
	void resolveEntryDecisionAllowsLongSetup1WhenAqua() throws Exception {
		CtiLbStrategy strategy = newStrategy();
		CtiLbStrategy.Indicators indicators = new CtiLbStrategy.Indicators(
				0.0120,
				0.55,
				0.95,
				0.0009,
				50.0,
				18.0,
				0.01,
				MacdHistColor.AQUA);
		CtiLbStrategy.EntryDecision decision = invokeResolveEntryDecision(strategy, CtiLbStrategy.PositionState.NONE,
				CtiDirection.LONG, indicators);
		assertThat(decision.confirmedRec()).isEqualTo(CtiDirection.LONG);
		assertThat(decision.matchedSetupName()).isEqualTo("SETUP_1");
	}

	@Test
	void resolveEntryDecisionAllowsS6WhenS2OnlyEnabled() throws Exception {
		CtiLbStrategy strategy = newStrategy();
		CtiLbStrategy.Indicators indicators = new CtiLbStrategy.Indicators(
				0.025,
				0.30,
				2.5,
				0.012,
				55.0,
				30.0,
				-0.01,
				MacdHistColor.RED);
		CtiLbStrategy.EntryDecision decision = invokeResolveEntryDecision(strategy, CtiLbStrategy.PositionState.NONE,
				CtiDirection.SHORT, indicators);
		assertThat(decision.confirmedRec()).isEqualTo(CtiDirection.SHORT);
		assertThat(decision.matchedSetupName()).isEqualTo("SETUP_S6");
	}

	@Test
	void resolveEntryDecisionBlocksShortWhenMacdNotRed() throws Exception {
		CtiLbStrategy strategy = newStrategy();
		CtiLbStrategy.Indicators indicators = new CtiLbStrategy.Indicators(
				0.015,
				0.18,
				1.2,
				0.006,
				50.0,
				20.0,
				0.01,
				MacdHistColor.AQUA);
		CtiLbStrategy.EntryDecision decision = invokeResolveEntryDecision(strategy, CtiLbStrategy.PositionState.NONE,
				CtiDirection.SHORT, indicators);
		assertThat(decision.confirmedRec()).isNull();
		assertThat(decision.blockReason()).isEqualTo("SHORT_MACD_COLOR_NOT_RED");
	}

	@Test
	void resolveEntryDecisionShortS2FilterFailVol() throws Exception {
		CtiLbStrategy strategy = newStrategy();
		CtiLbStrategy.Indicators indicators = new CtiLbStrategy.Indicators(
				0.012,
				0.25,
				0.8,
				0.0045,
				42.0,
				18.0,
				-0.02,
				MacdHistColor.RED);
		CtiLbStrategy.EntryDecision decision = invokeResolveEntryDecision(strategy, CtiLbStrategy.PositionState.NONE,
				CtiDirection.SHORT, indicators);
		assertThat(decision.confirmedRec()).isNull();
		assertThat(decision.blockReason()).isEqualTo("SHORT_S2_FILTER_FAIL_VOL");
	}

	@Test
	void resolveEntryDecisionShortS2FilterFailMacd() throws Exception {
		CtiLbStrategy strategy = newStrategy();
		CtiLbStrategy.Indicators indicators = new CtiLbStrategy.Indicators(
				0.012,
				0.25,
				1.1,
				0.0045,
				42.0,
				18.0,
				0.0,
				MacdHistColor.RED);
		CtiLbStrategy.EntryDecision decision = invokeResolveEntryDecision(strategy, CtiLbStrategy.PositionState.NONE,
				CtiDirection.SHORT, indicators);
		assertThat(decision.confirmedRec()).isNull();
		assertThat(decision.blockReason()).isEqualTo("SHORT_S2_FILTER_FAIL_MACD");
	}

	@Test
	void resolveEntryDecisionShortS2FilterFailPercentB() throws Exception {
		CtiLbStrategy strategy = newStrategy();
		CtiLbStrategy.Indicators indicators = new CtiLbStrategy.Indicators(
				0.012,
				0.40,
				1.1,
				0.0045,
				42.0,
				18.0,
				-0.02,
				MacdHistColor.RED);
		CtiLbStrategy.EntryDecision decision = invokeResolveEntryDecision(strategy, CtiLbStrategy.PositionState.NONE,
				CtiDirection.SHORT, indicators);
		assertThat(decision.confirmedRec()).isNull();
		assertThat(decision.blockReason()).isEqualTo("SHORT_S2_FILTER_FAIL_PB");
	}

	@Test
	void resolveBarsInPositionCountsFiveMinuteBars() throws Exception {
		long entryTime = 1_000_000L;
		CtiLbStrategy.EntryState entryState = new CtiLbStrategy.EntryState(
				CtiDirection.LONG,
				BigDecimal.ONE,
				entryTime,
				BigDecimal.ONE);
		int bars = invokeResolveBarsInPosition(entryState, entryTime + (10 * 60_000L));
		assertThat(bars).isEqualTo(3);
	}

	@Test
	void jsonlFieldsRespectMatchedSetupSide() {
		ObjectMapper mapper = new ObjectMapper();
		ObjectNode line = mapper.createObjectNode();
		CtiLbStrategy.Indicators indicators = new CtiLbStrategy.Indicators(
				0.0114,
				0.62,
				0.95,
				0.0007,
				42.0,
				13.2,
				0.00004,
				null);
		CtiLbStrategy.EntryDecision longDecision = CtiLbStrategy.EntryDecision
				.longMatch(CtiLbStrategy.LongEntrySetup.SETUP_1, indicators);
		CtiLbStrategy.addSetupMatchFields(mapper, line, longDecision);
		assertThat(line.path("longSetupMatched").asBoolean()).isTrue();
		assertThat(line.path("shortSetupMatched").asBoolean()).isFalse();
		assertThat(line.path("matchedSetupLong").asText()).isEqualTo("SETUP_1");
		assertThat(line.path("matchedSetupShort").isNull()).isTrue();
		ObjectNode lineShort = mapper.createObjectNode();
		CtiLbStrategy.EntryDecision shortDecision = CtiLbStrategy.EntryDecision
				.shortMatch(CtiLbStrategy.ShortEntrySetup.SETUP_S6, indicators);
		CtiLbStrategy.addSetupMatchFields(mapper, lineShort, shortDecision);
		assertThat(lineShort.path("longSetupMatched").asBoolean()).isFalse();
		assertThat(lineShort.path("shortSetupMatched").asBoolean()).isTrue();
		assertThat(lineShort.path("matchedSetupShort").asText()).isEqualTo("SETUP_S6");
		assertThat(lineShort.path("matchedSetupLong").isNull()).isTrue();
	}

	private static CtiLbStrategy.EntryDecision invokeResolveEntryDecision(CtiLbStrategy strategy,
			CtiLbStrategy.PositionState current, CtiDirection recommendationUsed, CtiLbStrategy.Indicators indicators)
			throws Exception {
		Method method = CtiLbStrategy.class.getDeclaredMethod("resolveEntryDecision", CtiLbStrategy.PositionState.class,
				CtiDirection.class, CtiLbStrategy.Indicators.class);
		method.setAccessible(true);
		return (CtiLbStrategy.EntryDecision) method.invoke(strategy, current, recommendationUsed, indicators);
	}

	private static int invokeResolveBarsInPosition(CtiLbStrategy.EntryState entryState, long nowMs) throws Exception {
		Method method = CtiLbStrategy.class.getDeclaredMethod("resolveBarsInPosition", CtiLbStrategy.EntryState.class,
				long.class);
		method.setAccessible(true);
		return (int) method.invoke(null, entryState, nowMs);
	}

	private static CtiLbStrategy newStrategy() {
		BinanceFuturesOrderClient orderClient = Mockito.mock(BinanceFuturesOrderClient.class);
		SymbolFilterService symbolFilterService = Mockito.mock(SymbolFilterService.class);
		OrderTracker orderTracker = Mockito.mock(OrderTracker.class);
		ObjectMapper objectMapper = new ObjectMapper();
		WarmupProperties warmupProperties = new WarmupProperties(true, 1, 1, 1, false, 0);
		return new CtiLbStrategy(orderClient, strategyProperties(), warmupProperties, symbolFilterService, orderTracker,
				objectMapper);
	}

	private static StrategyProperties strategyProperties() {
		LongSetupProperties longSetups = new LongSetupProperties(
				new LongSetupProperties.Setup1(0.0114, 0.0130, 0.8, 1.0),
				new LongSetupProperties.Setup2(0.0100, 0.0114, 1.0, 1.2),
				new LongSetupProperties.Setup3(0.0100, 0.0114, 0.0005, 0.0010),
				new LongSetupProperties.Setup4(0.0080, 0.0100, 0.0010, 0.0015),
				new LongSetupProperties.Setup5(35, 45, 0.004));
		ShortSetupProperties shortSetups = new ShortSetupProperties(
				new ShortSetupProperties.S1(0.0130, 0.0160, 0.0005, 0.0010),
				new ShortSetupProperties.S2(40, 45, 0.0040, 0.0060),
				new ShortSetupProperties.S3(0.0080, 0.0100, 55, 60),
				new ShortSetupProperties.S4(0.60, 0.75, 0.00001, 0.00005),
				new ShortSetupProperties.S5(12.5, 15.0, 0.00005),
				new ShortSetupProperties.S6(2.2, 0.020, 0.010, 25));
		return new StrategyProperties(
				StrategyType.CTI_LB,
				"REF",
				"TRADE",
				List.of("TRADE"),
				1,
				BigDecimal.ONE,
				1,
				"LONG",
				false,
				false,
				1,
				1,
				1,
				1,
				BigDecimal.ONE,
				BigDecimal.ONE,
				BigDecimal.ONE,
				BigDecimal.ONE,
				BigDecimal.ONE,
				BigDecimal.ONE,
				BigDecimal.ONE,
				BigDecimal.ONE,
				BigDecimal.ONE,
				BigDecimal.ONE,
				BigDecimal.ONE,
				1,
				1L,
				1,
				BigDecimal.ONE,
				BigDecimal.ONE,
				BigDecimal.ONE,
				BigDecimal.ONE,
				0.1,
				0.1,
				BigDecimal.ONE,
				BigDecimal.ONE,
				BigDecimal.ONE,
				1,
				BigDecimal.ONE,
				true,
				1,
				1,
				BigDecimal.ONE,
				BigDecimal.ONE,
				1,
				BigDecimal.ONE,
				BigDecimal.ONE,
				BigDecimal.ONE,
				1,
				1L,
				false,
				false,
				1L,
				1,
				1,
				BigDecimal.ONE,
				BigDecimal.ONE,
				false,
				1L,
				45.0,
				75.0,
				25.0,
				55.0,
				1.0,
				1.0,
				50,
				false,
				85.0,
				15.0,
				3.0,
				false,
				false,
				0.7,
				60.0,
				0.35,
				0.20,
				0.15,
				55,
				3,
				0.35,
				70,
				false,
				20.0,
				0.0015,
				60,
				40,
				false,
				0.25,
				0.15,
				false,
				1L,
				20.0,
				-20.0,
				1.0,
				1.0,
				1.0,
				1.0,
				1.0,
				1,
				false,
				20.0,
				20.0,
				true,
				0.010,
				true,
				45.0,
				60.0,
				0.80,
				true,
				true,
				true,
				2,
				true,
				true,
				1.0,
				true,
				0.30,
				true,
				2.2,
				0.020,
				0.010,
				25.0,
				true,
				1,
				longSetups,
				shortSetups);
	}
}
