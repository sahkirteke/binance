package com.binance.exchange;

import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import reactor.core.publisher.Mono;

@Component
public class TimeSyncServices {

	private static final Logger LOGGER = LoggerFactory.getLogger(TimeSyncServices.class);
	private static final long SYNC_INTERVAL_MS = 60_000L;

	private final WebClient binanceWebClient;
	private final AtomicLong offsetMs = new AtomicLong();

	public TimeSyncServices(WebClient binanceWebClient) {
		this.binanceWebClient = binanceWebClient;
		syncNow().subscribe();
	}

	public long currentTimestampMillis() {
		return System.currentTimeMillis() + offsetMs.get();
	}

	public Mono<Long> syncNow() {
		return binanceWebClient
				.get()
				.uri("/fapi/v1/time")
				.retrieve()
				.bodyToMono(ServerTimeResponse.class)
				.map(response -> {
					long offset = response.serverTime() - System.currentTimeMillis();
					offsetMs.set(offset);
					return offset;
				})
				.doOnNext(offset -> LOGGER.info("EVENT=TIME_SYNC offsetMs={}", offset))
				.doOnError(error -> LOGGER.warn("EVENT=TIME_SYNC_FAIL reason={}", error.getMessage()));
	}

	@Scheduled(fixedDelay = SYNC_INTERVAL_MS)
	void scheduledSync() {
		syncNow().subscribe();
	}

	private record ServerTimeResponse(long serverTime) {
	}
}
