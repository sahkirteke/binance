package com.binance.exchange;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

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
	private final AtomicReference<Mono<Long>> inFlightSync = new AtomicReference<>();

	public TimeSyncServices(WebClient binanceWebClient) {
		this.binanceWebClient = binanceWebClient;
		syncNow().subscribe(ignored -> { }, error -> LOGGER.warn("EVENT=TIME_SYNC_SUBSCRIBE_FAIL reason={}",
				error.getMessage()));
	}

	public long currentTimestampMillis() {
		return System.currentTimeMillis() + offsetMs.get();
	}

	public Mono<Long> syncNow() {
		Mono<Long> existing = inFlightSync.get();
		if (existing != null) {
			return existing;
		}
		long lastOffset = offsetMs.get();
		Mono<Long> syncMono = binanceWebClient
				.get()
				.uri("/fapi/v1/time")
				.retrieve()
				.bodyToMono(ServerTimeResponse.class)
				.map(response -> {
					long offset = response.serverTime() - System.currentTimeMillis();
					offsetMs.set(offset);
					return offset;
				})
				.doOnError(error -> LOGGER.warn("EVENT=TIME_SYNC_FAIL reason={}", error.getMessage()))
				.onErrorReturn(lastOffset)
				.doFinally(ignored -> inFlightSync.set(null))
				.cache();
		if (inFlightSync.compareAndSet(null, syncMono)) {
			return syncMono;
		}
		return inFlightSync.get();
	}

	@Scheduled(fixedDelay = SYNC_INTERVAL_MS)
	void scheduledSync() {
		syncNow().subscribe(ignored -> { }, error -> LOGGER.warn("EVENT=TIME_SYNC_SCHEDULE_FAIL reason={}",
				error.getMessage()));
	}

	private record ServerTimeResponse(long serverTime) {
	}
}
