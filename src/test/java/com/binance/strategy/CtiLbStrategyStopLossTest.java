package com.binance.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.binance.exchange.BinanceFuturesOrderClient;
import com.fasterxml.jackson.databind.ObjectMapper;

class CtiLbStrategyStopLossTest {

	@Test
	void resolveExpectedStopPriceRoundsByTick() {
		BinanceFuturesOrderClient orderClient = Mockito.mock(BinanceFuturesOrderClient.class);
		SymbolFilterService symbolFilterService = Mockito.mock(SymbolFilterService.class);
		OrderTracker orderTracker = Mockito.mock(OrderTracker.class);
		ObjectMapper objectMapper = new ObjectMapper();
		WarmupProperties warmupProperties = new WarmupProperties(true, 1, 1, 1, false, 0);
		StrategyProperties properties = CtiLbStrategyTestHelper.strategyProperties();
		CtiLbStrategy strategy = new CtiLbStrategy(orderClient, properties, warmupProperties, symbolFilterService,
				orderTracker, objectMapper);

		BinanceFuturesOrderClient.SymbolFilters filters = new BinanceFuturesOrderClient.SymbolFilters(
				BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, new BigDecimal("0.05"));
		Mockito.when(symbolFilterService.getFilters("TEST")).thenReturn(filters);

		Double longStop = strategy.resolveExpectedStopPriceForAudit("TEST", CtiDirection.LONG,
				BigDecimal.valueOf(100.0));
		Double shortStop = strategy.resolveExpectedStopPriceForAudit("TEST", CtiDirection.SHORT,
				BigDecimal.valueOf(100.0));

		assertThat(longStop).isEqualTo(99.8);
		assertThat(shortStop).isEqualTo(100.2);
	}

	@Test
	void calculateRealizedPnlMatchesDirection() throws Exception {
		CtiLbStrategy strategy = CtiLbStrategyTestHelper.newStrategy();
		CtiLbStrategy.EntryState entryLong = new CtiLbStrategy.EntryState(
				CtiDirection.LONG, BigDecimal.valueOf(100), 0L, BigDecimal.ONE);
		CtiLbStrategy.EntryState entryShort = new CtiLbStrategy.EntryState(
				CtiDirection.SHORT, BigDecimal.valueOf(100), 0L, BigDecimal.ONE);
		java.lang.reflect.Method method = CtiLbStrategy.class.getDeclaredMethod(
				"calculateRealizedPnl", CtiLbStrategy.EntryState.class, BigDecimal.class, double.class);
		method.setAccessible(true);
		BigDecimal longPnl = (BigDecimal) method.invoke(strategy, entryLong, BigDecimal.ONE, 99.8);
		BigDecimal shortPnl = (BigDecimal) method.invoke(strategy, entryShort, BigDecimal.ONE, 100.2);
		assertThat(longPnl).isEqualByComparingTo(BigDecimal.valueOf(-0.2));
		assertThat(shortPnl).isEqualByComparingTo(BigDecimal.valueOf(-0.2));
	}

	static class CtiLbStrategyTestHelper {
		static CtiLbStrategy newStrategy() {
			BinanceFuturesOrderClient orderClient = Mockito.mock(BinanceFuturesOrderClient.class);
			SymbolFilterService symbolFilterService = Mockito.mock(SymbolFilterService.class);
			OrderTracker orderTracker = Mockito.mock(OrderTracker.class);
			ObjectMapper objectMapper = new ObjectMapper();
			WarmupProperties warmupProperties = new WarmupProperties(true, 1, 1, 1, false, 0);
			return new CtiLbStrategy(orderClient, strategyProperties(), warmupProperties, symbolFilterService,
					orderTracker, objectMapper);
		}

		static StrategyProperties strategyProperties() {
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
					0.002,
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
					true,
					true,
					2,
					0.0025,
					true,
					4,
					true,
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
					0.008,
					2.2,
					1,
					1,
					1,
					1.0,
					1.0,
					1.0,
					0.6,
					1.0,
					1.30,
					1.70,
					true,
					1,
					longSetups,
					shortSetups);
		}
	}
}
