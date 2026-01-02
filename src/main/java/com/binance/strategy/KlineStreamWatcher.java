package com.binance.strategy;

import java.net.URI;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;

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

	private final StrategyProperties strategyProperties;
	private final StrategyRouter strategyRouter;
	private final ObjectMapper objectMapper;
	private final ReactorNettyWebSocketClient webSocketClient = new ReactorNettyWebSocketClient();
	private final AtomicReference<Disposable> subscriptionRef = new AtomicReference<>();

	public KlineStreamWatcher(StrategyProperties strategyProperties,
			StrategyRouter strategyRouter,
			ObjectMapper objectMapper) {
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
		String symbol = strategyProperties.tradeSymbol().toLowerCase();
		URI uri = URI.create("wss://stream.binance.com:9443/ws/" + symbol + "@kline_" + KLINE_INTERVAL);
		Disposable subscription = webSocketClient.execute(uri, session -> session.receive()
				.map(message -> message.getPayloadAsText())
				.doOnNext(this::handleKlineMessage)
				.then())
				.retryWhen(Retry.backoff(Long.MAX_VALUE, java.time.Duration.ofSeconds(1)))
				.subscribe();
		subscriptionRef.set(subscription);
		LOGGER.info("Kline stream started for {} interval {}", symbol, KLINE_INTERVAL);
	}

	@PreDestroy
	public void stop() {
		Disposable subscription = subscriptionRef.getAndSet(null);
		if (subscription != null) {
			subscription.dispose();
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
			strategyRouter.onClosedCandle(kline.close(), kline.closeTime());
		} catch (Exception ex) {
			LOGGER.warn("Failed to parse kline message", ex);
		}
	}
}
