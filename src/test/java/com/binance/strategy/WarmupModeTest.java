package com.binance.strategy;

import static org.mockito.Mockito.verifyNoInteractions;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.binance.exchange.BinanceFuturesOrderClient;

class WarmupModeTest {

	@Test
	void warmupModeAvoidsOrderClientCalls() {
		BinanceFuturesOrderClient orderClient = org.mockito.Mockito.mock(BinanceFuturesOrderClient.class);
		StrategyProperties properties = new StrategyProperties(
				StrategyType.CTI_LB,
				"BTCUSDT",
				"BTCUSDT",
				List.of("BTCUSDT"),
				50,
				BigDecimal.ONE,
				2,
				"LONG",
				true,
				false,
				500,
				100,
				800,
				1,
				12,
				BigDecimal.valueOf(0.25),
				BigDecimal.valueOf(0.05),
				BigDecimal.valueOf(0.05),
				BigDecimal.valueOf(0.04),
				BigDecimal.valueOf(0.02),
				BigDecimal.valueOf(0.03),
				BigDecimal.valueOf(0.03),
				BigDecimal.valueOf(0.03),
				BigDecimal.valueOf(0.92),
				BigDecimal.valueOf(0.98),
				BigDecimal.valueOf(0.98),
				180,
				450L,
				650,
				BigDecimal.valueOf(10),
				BigDecimal.valueOf(50),
				BigDecimal.valueOf(18),
				BigDecimal.valueOf(22),
				BigDecimal.ONE,
				BigDecimal.valueOf(0.01),
				BigDecimal.valueOf(100),
				3,
				BigDecimal.valueOf(50),
				true,
				30000,
				60000,
				BigDecimal.valueOf(0.085),
				BigDecimal.valueOf(0.06),
				2,
				BigDecimal.valueOf(0.002),
				BigDecimal.valueOf(10),
				BigDecimal.valueOf(12),
				10,
				2500L,
				false,
				false,
				300_000L,
				5,
				12,
				BigDecimal.valueOf(1.0),
				BigDecimal.valueOf(0.40),
				1,
				1,
				1,
				true,
				2000L,
				70,
				40,
				45,
				75,
				25,
				55,
				1.5,
				2.0,
				2,
				1,
				0.35,
				0.20,
				0.15,
				55,
				3,
				0.35,
				70,
				2,
				true);
		WarmupProperties warmupProperties = new WarmupProperties(true, 240, 120, 3, false, 0);
		SymbolFilterService filterService = new SymbolFilterService(orderClient, properties);
		OrderTracker orderTracker = new OrderTracker();
		CtiLbStrategy strategy = new CtiLbStrategy(orderClient, properties, warmupProperties, filterService,
				orderTracker);
		strategy.setWarmupMode(true);

		ScoreSignal signal = new ScoreSignal(
				CtiDirection.NEUTRAL,
				CtiDirection.NEUTRAL,
				0,
				0,
				0,
				0.1,
				0.1,
				0.0,
				0.0,
				20.0,
				0,
				0.0,
				0.0,
				CtiDirection.NEUTRAL,
				CtiDirection.NEUTRAL,
				CtiScoreCalculator.RecReason.TIE_HOLD,
				false,
				true,
				"ADX5M<=20",
				true,
				21,
				21,
				14,
				14,
				0L,
				1_000_000L,
				false);
		Candle candle = new Candle(100.0, 101.0, 99.0, 100.0, 1200.0, 1_000_000L);
		strategy.onScoreSignal("BTCUSDT", signal, candle);

		verifyNoInteractions(orderClient);
	}
}
