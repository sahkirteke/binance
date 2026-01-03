package com.binance.strategy;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;

import com.binance.config.BinanceProperties;
import com.binance.market.dto.KlineEvent;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import reactor.core.Disposable;
import reactor.util.retry.Retry;

@Component
public class KlineStreamWatcher {

	private static final Logger LOGGER = LoggerFactory.getLogger(KlineStreamWatcher.class);
	private static final String KLINE_INTERVAL = "1m";

	private final BinanceProperties binanceProperties;
	private final StrategyProperties strategyProperties;
	private final StrategyRouter strategyRouter;
	private final ObjectMapper objectMapper;
	private final ReactorNettyWebSocketClient webSocketClient = new ReactorNettyWebSocketClient();
	private final AtomicReference<List<Disposable>> subscriptionRef = new AtomicReference<>();

	public KlineStreamWatcher(BinanceProperties binanceProperties,
			StrategyProperties strategyProperties,
			StrategyRouter strategyRouter,
			ObjectMapper objectMapper) {
		this.binanceProperties = binanceProperties;
		this.strategyProperties = strategyProperties;
		this.strategyRouter = strategyRouter;
		this.objectMapper = objectMapper;
	}

	@PostConstruct
	public void start() {
		if (strategyProperties.active() != StrategyType.CTI_LB) {
			LOGGER.info("Kline stream not started (active={})", strategyProperties.active());
			return;
		}
		String baseUrl = binanceProperties.useTestnet()
				? "wss://stream.binancefuture.com/ws/"
				: "wss://fstream.binance.com/ws/";
		List<Disposable> subscriptions = new ArrayList<>();
		for (String symbol : strategyProperties.resolvedTradeSymbols()) {
			String lowerSymbol = symbol.toLowerCase();
			URI uri = URI.create(baseUrl + lowerSymbol + "@kline_" + KLINE_INTERVAL);
			Disposable subscription = webSocketClient.execute(uri, session -> session.receive()
					.map(message -> message.getPayloadAsText())
					.doOnNext(this::handleKlineMessage)
					.then())
					.retryWhen(Retry.backoff(Long.MAX_VALUE, java.time.Duration.ofSeconds(1)))
					.subscribe();
			subscriptions.add(subscription);
			LOGGER.info("Kline stream started for {} interval {}", lowerSymbol, KLINE_INTERVAL);
		}
		subscriptionRef.set(subscriptions);
	}

	@PreDestroy
	public void stop() {
		List<Disposable> subscriptions = subscriptionRef.getAndSet(null);
		if (subscriptions != null) {
			subscriptions.forEach(Disposable::dispose);
		}
	}

	private void handleKlineMessage(String payload) {
		try {
			KlineEvent event = objectMapper.readValue(payload, KlineEvent.class);
			if (event == null || event.kline() == null) {
				return;
			}
			KlineEvent.Kline kline = event.kline();
			if (!kline.closed()) {
				return;
			}
			strategyRouter.onClosedCandle(event.symbol(), kline.close(), kline.closeTime());
		} catch (Exception ex) {
			LOGGER.warn("Failed to parse kline message", ex);
		}
	}
}
