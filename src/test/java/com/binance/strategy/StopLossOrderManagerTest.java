package com.binance.strategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.binance.exchange.BinanceFuturesOrderClient;
import com.fasterxml.jackson.databind.ObjectMapper;

class StopLossOrderManagerTest {

	private static final String SYMBOL = "BTCUSDT";

	private StrategyProperties properties;
	private SymbolFilterService symbolFilterService;
	private StopLossOrderManager manager;
	private TestTriggerHandler triggerHandler;

	@BeforeEach
	void setUp() {
		properties = mock(StrategyProperties.class);
		when(properties.stopLossPct()).thenReturn(0.002);
		when(properties.stopLossBps()).thenReturn(null);
		when(properties.priceTick()).thenReturn(BigDecimal.valueOf(0.01));
		when(properties.stopLoss()).thenReturn(new StopLossProperties(StopLossMode.PAPER));

		symbolFilterService = mock(SymbolFilterService.class);
		when(symbolFilterService.getFilters(SYMBOL))
				.thenReturn(new BinanceFuturesOrderClient.SymbolFilters(null, null, null, BigDecimal.valueOf(0.01)));

		PaperStopLossOrderExecutor paperExecutor = new PaperStopLossOrderExecutor();
		LiveStopLossOrderExecutor liveExecutor = new LiveStopLossOrderExecutor();
		triggerHandler = new TestTriggerHandler();
		manager = new StopLossOrderManager(properties, symbolFilterService, paperExecutor, liveExecutor,
				new ObjectMapper());
		manager.registerTriggerHandler(triggerHandler);
	}

	@Test
	void longStopTriggersWhenPriceBelowStop() {
		manager.onEntryFilled(SYMBOL, CtiDirection.LONG, BigDecimal.valueOf(100.0));
		StopLossOrderManager.StopLossOrderState order = manager.activeOrderForTest(SYMBOL);
		assertThat(order).isNotNull();
		assertThat(order.stopPrice()).isEqualTo(99.8);

		manager.onPriceUpdate(SYMBOL, null, 99.79);

		assertThat(triggerHandler.triggers).hasSize(1);
		assertThat(manager.activeOrderForTest(SYMBOL)).isNull();
	}

	@Test
	void shortStopTriggersWhenPriceAboveStop() {
		manager.onEntryFilled(SYMBOL, CtiDirection.SHORT, BigDecimal.valueOf(100.0));
		StopLossOrderManager.StopLossOrderState order = manager.activeOrderForTest(SYMBOL);
		assertThat(order).isNotNull();
		assertThat(order.stopPrice()).isEqualTo(100.2);

		manager.onPriceUpdate(SYMBOL, null, 100.21);

		assertThat(triggerHandler.triggers).hasSize(1);
		assertThat(manager.activeOrderForTest(SYMBOL)).isNull();
	}

	@Test
	void roundsStopPriceBySide() {
		manager.onEntryFilled(SYMBOL, CtiDirection.LONG, BigDecimal.valueOf(100.005));
		StopLossOrderManager.StopLossOrderState longOrder = manager.activeOrderForTest(SYMBOL);
		assertThat(longOrder.stopPrice()).isEqualTo(99.80);
		manager.onPositionClosed(SYMBOL);

		manager.onEntryFilled(SYMBOL, CtiDirection.SHORT, BigDecimal.valueOf(100.005));
		StopLossOrderManager.StopLossOrderState shortOrder = manager.activeOrderForTest(SYMBOL);
		assertThat(shortOrder.stopPrice()).isEqualTo(100.21);
	}

	@Test
	void clearsOrderAfterClose() {
		manager.onEntryFilled(SYMBOL, CtiDirection.LONG, BigDecimal.valueOf(100.0));
		assertThat(manager.activeOrderForTest(SYMBOL)).isNotNull();

		manager.onPositionClosed(SYMBOL);

		assertThat(manager.activeOrderForTest(SYMBOL)).isNull();
	}

	private static class TestTriggerHandler implements StopLossTriggerHandler {
		private final List<StopLossTrigger> triggers = new ArrayList<>();

		@Override
		public void onStopLossTriggered(StopLossTrigger trigger) {
			triggers.add(trigger);
		}
	}
}
