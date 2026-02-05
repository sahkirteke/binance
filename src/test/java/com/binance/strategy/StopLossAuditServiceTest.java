package com.binance.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class StopLossAuditServiceTest {

	@Test
	void tradeTruthFlagsStopLossDistance() {
		StopLossAuditService.TradeTruthRecorder.ExitContext exitContext =
				new StopLossAuditService.TradeTruthRecorder.ExitContext(
						"EXIT_STOP_LOSS",
						0.002,
						99.8,
						99.8);
		OrderTracker.OrderUpdate entryUpdate = new OrderTracker.OrderUpdate(
				"TEST",
				1L,
				"FILLED",
				"TRADE",
				"BUY",
				"LONG",
				"cid",
				false,
				1000L,
				"100.0",
				"1.0",
				"1.0",
				"MARKET",
				"",
				"",
				false);
		OrderTracker.OrderUpdate exitUpdate = new OrderTracker.OrderUpdate(
				"TEST",
				2L,
				"FILLED",
				"TRADE",
				"SELL",
				"LONG",
				"cid",
				true,
				2000L,
				"99.8",
				"1.0",
				"1.0",
				"MARKET",
				"",
				"",
				false);
		StopLossAuditService.TradeTruthRecorder recorder = new StopLossAuditService.TradeTruthRecorder();
		final StopLossAuditService.TradeTruthRecorder.TradeTruth[] truthHolder =
				new StopLossAuditService.TradeTruthRecorder.TradeTruth[1];
		recorder.onOrderUpdate(entryUpdate, exitContext, truth -> truthHolder[0] = truth);
		recorder.onOrderUpdate(exitUpdate, exitContext, truth -> truthHolder[0] = truth);

		assertThat(truthHolder[0]).isNotNull();
		assertThat(truthHolder[0].isStopLossExit()).isTrue();
		assertThat(truthHolder[0].isStopLossDistanceOk(0.0002)).isTrue();
		assertThat(truthHolder[0].isStopPriceConsistent(0.0005)).isTrue();
	}
}
