package com.binance.exchange;

import java.net.ConnectException;
import java.time.Duration;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import reactor.core.Exceptions;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;
import reactor.netty.http.client.PrematureCloseException;
import reactor.util.retry.Retry;

import io.netty.channel.ChannelOption;

@Component
public class TimeSyncServices {

	private static final Logger LOGGER = LoggerFactory.getLogger(TimeSyncServices.class);

	// İstersen kendi interval’ine göre ayarla
	private static final long SYNC_INTERVAL_MS = 10 * 60_000L; // 10 dk
	private static final Duration REQ_TIMEOUT = Duration.ofSeconds(3);

	private final AtomicLong offsetMs = new AtomicLong(0);
	private final AtomicReference<Mono<Long>> inFlightSync = new AtomicReference<>(null);

	// Time endpoint için: POOL YOK -> her seferinde yeni connection
	private final WebClient timeClient;

	public TimeSyncServices() {
		HttpClient hc = HttpClient.create(ConnectionProvider.newConnection())
				.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 3000)
				.responseTimeout(REQ_TIMEOUT)
				.keepAlive(false);

		this.timeClient = WebClient.builder()
				.baseUrl("https://fapi.binance.com")
				.clientConnector(new ReactorClientHttpConnector(hc))
				.defaultHeader(HttpHeaders.CONNECTION, "close")
				.build();

		// Başlangıçta bir kez dene (opsiyonel)
		syncNow().subscribe(
				ok -> { },
				err -> LOGGER.warn("EVENT=TIME_SYNC_SUBSCRIBE_FAIL reason={}", rootMessage(err))
		);
	}

	public long currentTimestampMillis() {
		return System.currentTimeMillis() + offsetMs.get();
	}

	public Mono<Long> syncNow() {
		Mono<Long> existing = inFlightSync.get();
		if (existing != null) return existing;

		Mono<Long> mono = Mono.defer(() -> {
					long t0 = System.currentTimeMillis();
					return timeClient.get()
							.uri("/fapi/v1/time")
							.retrieve()
							.bodyToMono(ServerTimeResponse.class)
							.timeout(REQ_TIMEOUT)
							.map(resp -> {
								long t1 = System.currentTimeMillis();
								long rtt = Math.max(0, t1 - t0);
								// RTT/2 düzeltmesiyle offset
								long newOffset = resp.serverTime() - (t0 + (rtt / 2));
								offsetMs.set(newOffset);
								LOGGER.info("EVENT=TIME_SYNC_OK offsetMs={} rttMs={}", newOffset, rtt);
								return newOffset;
							});
				})
				.retryWhen(Retry.backoff(3, Duration.ofMillis(200))
						.maxBackoff(Duration.ofSeconds(2))
						.jitter(0.2)
						.filter(TimeSyncServices::isRetryable)
				)
				.doOnError(e -> LOGGER.warn("EVENT=TIME_SYNC_FAIL reason={}", rootMessage(e)))
				.doFinally(sig -> inFlightSync.set(null))
				.cache(); // aynı anda birden fazla çağrı olursa tek istek

		if (inFlightSync.compareAndSet(null, mono)) return mono;
		return inFlightSync.get();
	}

	@Scheduled(fixedDelay = SYNC_INTERVAL_MS)
	void scheduledSync() {
		syncNow().subscribe(
				ignored -> { },
				error -> LOGGER.warn("EVENT=TIME_SYNC_SCHEDULE_FAIL reason={}", rootMessage(error))
		);
	}

	private static boolean isRetryable(Throwable t) {
		Throwable e = Exceptions.unwrap(t);
		if (e instanceof PrematureCloseException) return true;
		if (e instanceof TimeoutException) return true;
		if (e instanceof ConnectException) return true;
		String msg = e.getMessage();
		return msg != null && msg.contains("Connection prematurely closed");
	}

	private static String rootMessage(Throwable t) {
		Throwable e = Exceptions.unwrap(t);
		return (e.getMessage() != null) ? e.getMessage() : e.getClass().getSimpleName();
	}

	private record ServerTimeResponse(long serverTime) { }
}
