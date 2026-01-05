package com.binance.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.binance.exchange.BinanceFuturesOrderClient;

class SymbolFilterServiceTest {

	@Test
	void parseExchangeInfoFiltersForTrackedSymbols() {
		BinanceFuturesOrderClient.ExchangeFilter lotSize = new BinanceFuturesOrderClient.ExchangeFilter(
				"LOT_SIZE",
				new BigDecimal("0.1"),
				null,
				null,
				new BigDecimal("0.1"),
				null);
		BinanceFuturesOrderClient.ExchangeFilter minNotional = new BinanceFuturesOrderClient.ExchangeFilter(
				"MIN_NOTIONAL",
				null,
				new BigDecimal("5"),
				null,
				null,
				null);
		BinanceFuturesOrderClient.ExchangeFilter priceFilter = new BinanceFuturesOrderClient.ExchangeFilter(
				"PRICE_FILTER",
				null,
				null,
				null,
				null,
				new BigDecimal("0.01"));
		BinanceFuturesOrderClient.SymbolInfo etcInfo = new BinanceFuturesOrderClient.SymbolInfo(
				"ETCUSDT",
				List.of(lotSize, minNotional, priceFilter));
		BinanceFuturesOrderClient.SymbolInfo otherInfo = new BinanceFuturesOrderClient.SymbolInfo(
				"OTHERUSDT",
				List.of(lotSize));
		BinanceFuturesOrderClient.ExchangeInfoResponse response = new BinanceFuturesOrderClient.ExchangeInfoResponse(
				List.of(etcInfo, otherInfo));

		Map<String, BinanceFuturesOrderClient.SymbolFilters> parsed = SymbolFilterService.parseExchangeInfo(
				response,
				List.of("ETCUSDT", "XRPUSDT"));

		assertThat(parsed).containsKey("ETCUSDT");
		assertThat(parsed).doesNotContainKey("OTHERUSDT");
		BinanceFuturesOrderClient.SymbolFilters filters = parsed.get("ETCUSDT");
		assertThat(filters.minQty()).isEqualByComparingTo("0.1");
		assertThat(filters.stepSize()).isEqualByComparingTo("0.1");
		assertThat(filters.minNotional()).isEqualByComparingTo("5");
		assertThat(filters.tickSize()).isEqualByComparingTo("0.01");
	}
}
