package com.binance.strategy;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.binance.exchange.BinanceFuturesOrderClient;
import com.binance.market.BinanceMarketClient;
import com.fasterxml.jackson.databind.ObjectMapper;

@Configuration
public class StrategyConfiguration {

	@Bean
	public EtcEthDepthStrategyWatcher etcEthDepthStrategyWatcher(BinanceMarketClient marketClient,
			BinanceFuturesOrderClient orderClient,
			StrategyProperties strategyProperties,
			ObjectMapper objectMapper) {
		return new EtcEthDepthStrategyWatcher(marketClient, orderClient, strategyProperties, objectMapper);
	}
}
