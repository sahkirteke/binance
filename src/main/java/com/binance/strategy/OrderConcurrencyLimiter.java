package com.binance.strategy;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.binance.exchange.dto.OrderResponse;

import reactor.core.publisher.Mono;

@Component
public class OrderConcurrencyLimiter {
	private static final Logger LOGGER = LoggerFactory.getLogger(OrderConcurrencyLimiter.class);
	private static final Set<String> FINAL_STATUSES = Set.of("FILLED", "CANCELED", "REJECTED", "EXPIRED");

	public enum OrderQueueMode {
		QUEUE,
		SKIP
	}

	public enum OrderPriority {
		EXIT,
		ENTRY
	}

	public enum QueueDecision {
		EXECUTED,
		QUEUED,
		SKIPPED
	}

	private final StrategyProperties properties;
	private final Semaphore semaphore;
	private final AtomicInteger inFlight = new AtomicInteger(0);
	private final AtomicInteger entryInFlight = new AtomicInteger(0);
	private final Map<Long, Permit> permitsByOrderId = new ConcurrentHashMap<>();
	private final Deque<QueuedOrder> queue = new ArrayDeque<>();
	private final ExecutorService executor = Executors.newSingleThreadExecutor();
	private volatile long activeBarKey = Long.MIN_VALUE;
	private volatile int barEntryCountUsed;
	private volatile int barEntryLimitMax;

	public OrderConcurrencyLimiter(StrategyProperties properties) {
		this.properties = properties;
		this.semaphore = new Semaphore(Math.max(1, properties.maxConcurrentOrders()), true);
	}

	public int maxConcurrentOrders() {
		return Math.max(1, properties.maxConcurrentOrders());
	}

	public int inFlightCount() {
		return inFlight.get();
	}

	public int barEntryLimitMax() {
		return barEntryLimitMax;
	}

	public int barEntryCountUsed() {
		return barEntryCountUsed;
	}

	public void updateBarState(long barKey, int barEntryCountUsed, int barEntryLimitMax) {
		this.activeBarKey = barKey;
		this.barEntryCountUsed = barEntryCountUsed;
		this.barEntryLimitMax = barEntryLimitMax;
		drainQueue();
	}

	public QueueResult submitEntry(long barKey, Supplier<Mono<OrderResponse>> orderSupplier) {
		if (barEntryLimitMax > 0 && barEntryCountUsed >= barEntryLimitMax) {
			return enqueueOrSkip(barKey, orderSupplier, "BAR_ENTRY_LIMIT_REACHED");
		}
		Permit permit = tryAcquirePermit(OrderPriority.ENTRY);
		if (permit == null) {
			return enqueueOrSkip(barKey, orderSupplier, "PORTFOLIO_CONCURRENCY_LIMIT");
		}
		executeWithPermit(permit, orderSupplier);
		return new QueueResult(QueueDecision.EXECUTED, null);
	}

	public Mono<OrderResponse> submitExit(Supplier<Mono<OrderResponse>> orderSupplier) {
		return Mono.fromCallable(() -> acquirePermit(OrderPriority.EXIT))
				.flatMap(permit -> orderSupplier.get()
						.doOnNext(response -> registerOrder(response, permit))
						.doOnError(error -> releasePermit(permit)))
				.doOnError(error -> LOGGER.warn("EVENT=EXIT_ORDER_SUBMIT_FAIL reason={}", error.getMessage()));
	}

	public void registerOrder(OrderResponse response, Permit permit) {
		if (response == null || response.orderId() == null) {
			releasePermit(permit);
			return;
		}
		permitsByOrderId.put(response.orderId(), permit.withOrderId(response.orderId()));
	}

	public OrderMeta lookupOrderMeta(long orderId) {
		Permit permit = permitsByOrderId.get(orderId);
		if (permit == null) {
			return null;
		}
		return new OrderMeta(permit.acquireTimeMs(), permit.releaseTimeMs(), permit.queueWaitMs(),
				permit.priority().name());
	}

	public void handleOrderUpdate(OrderTracker.OrderUpdate update) {
		if (update == null) {
			return;
		}
		if (!FINAL_STATUSES.contains(update.status())) {
			return;
		}
		Permit permit = permitsByOrderId.remove(update.orderId());
		if (permit != null) {
			permit.setReleaseTimeMs(System.currentTimeMillis());
			releasePermit(permit);
		}
	}

	private QueueResult enqueueOrSkip(long barKey, Supplier<Mono<OrderResponse>> orderSupplier, String reason) {
		OrderQueueMode mode = resolveQueueMode();
		if (mode == OrderQueueMode.SKIP) {
			LOGGER.info("EVENT=ORDER_QUEUE_SKIP reason={}", reason);
			return new QueueResult(QueueDecision.SKIPPED, reason);
		}
		synchronized (queue) {
			if (queue.size() >= Math.max(1, properties.queueMaxSize())) {
				LOGGER.warn("EVENT=ORDER_QUEUE_FULL_DISCARD size={} reason={}", queue.size(), reason);
				return new QueueResult(QueueDecision.SKIPPED, "ORDER_QUEUE_FULL_DISCARD");
			}
			queue.addLast(new QueuedOrder(barKey, orderSupplier, System.currentTimeMillis(), reason));
		}
		return new QueueResult(QueueDecision.QUEUED, reason);
	}

	private void drainQueue() {
		executor.execute(() -> {
			while (true) {
				QueuedOrder queued;
				synchronized (queue) {
					queued = queue.peekFirst();
					if (queued == null) {
						return;
					}
					if (!isQueueEligible(queued)) {
						return;
					}
					queue.removeFirst();
				}
				Permit permit = tryAcquirePermit(OrderPriority.ENTRY);
				if (permit == null) {
					synchronized (queue) {
						queue.addFirst(queued);
					}
					return;
				}
				long waitMs = System.currentTimeMillis() - queued.enqueueTimeMs();
				permit.setQueueWaitMs(waitMs);
				executeWithPermit(permit, queued.orderSupplier());
			}
		});
	}

	private boolean isQueueEligible(QueuedOrder queued) {
		if (properties.queueMaxWaitMs() > 0
				&& System.currentTimeMillis() - queued.enqueueTimeMs() > properties.queueMaxWaitMs()) {
			LOGGER.warn("EVENT=ORDER_QUEUE_TIMEOUT_DISCARD reason={}", queued.reason());
			return false;
		}
		if (queued.barKey() == activeBarKey && barEntryLimitMax > 0 && barEntryCountUsed >= barEntryLimitMax) {
			return false;
		}
		return true;
	}

	private void executeWithPermit(Permit permit, Supplier<Mono<OrderResponse>> orderSupplier) {
		orderSupplier.get()
				.doOnNext(response -> registerOrder(response, permit))
				.doOnError(error -> releasePermit(permit))
				.subscribe();
	}

	private Permit tryAcquirePermit(OrderPriority priority) {
		if (priority == OrderPriority.ENTRY && !tryReserveEntrySlot()) {
			return null;
		}
		if (!semaphore.tryAcquire()) {
			if (priority == OrderPriority.ENTRY) {
				entryInFlight.decrementAndGet();
			}
			return null;
		}
		inFlight.incrementAndGet();
		return new Permit(priority, System.currentTimeMillis());
	}

	private Permit acquirePermit(OrderPriority priority) throws InterruptedException {
		semaphore.acquire();
		inFlight.incrementAndGet();
		return new Permit(priority, System.currentTimeMillis());
	}

	private void releasePermit(Permit permit) {
		if (permit == null) {
			return;
		}
		if (permit.priority() == OrderPriority.ENTRY) {
			entryInFlight.decrementAndGet();
		}
		inFlight.decrementAndGet();
		semaphore.release();
		drainQueue();
	}

	private boolean tryReserveEntrySlot() {
		int entryLimit = resolveEntryLimit();
		if (entryLimit <= 0) {
			return true;
		}
		while (true) {
			int current = entryInFlight.get();
			if (current >= entryLimit) {
				return false;
			}
			if (entryInFlight.compareAndSet(current, current + 1)) {
				return true;
			}
		}
	}

	private int resolveEntryLimit() {
		int maxConcurrent = maxConcurrentOrders();
		if (!properties.priorityExitOverEntry() || maxConcurrent <= 1) {
			return 0;
		}
		return maxConcurrent - 1;
	}

	private OrderQueueMode resolveQueueMode() {
		String mode = properties.orderQueueMode() == null ? "" : properties.orderQueueMode();
		try {
			return OrderQueueMode.valueOf(mode);
		} catch (IllegalArgumentException ex) {
			return OrderQueueMode.QUEUE;
		}
	}

	private record QueuedOrder(long barKey, Supplier<Mono<OrderResponse>> orderSupplier,
			long enqueueTimeMs, String reason) {
	}

	private static final class Permit {
		private final OrderPriority priority;
		private final long acquireTimeMs;
		private long releaseTimeMs;
		private long queueWaitMs;
		private long orderId;

		private Permit(OrderPriority priority, long acquireTimeMs) {
			this.priority = priority;
			this.acquireTimeMs = acquireTimeMs;
		}

		private Permit withOrderId(long orderId) {
			this.orderId = orderId;
			return this;
		}

		private void setReleaseTimeMs(long releaseTimeMs) {
			this.releaseTimeMs = releaseTimeMs;
		}

		private void setQueueWaitMs(long queueWaitMs) {
			this.queueWaitMs = queueWaitMs;
		}

		private OrderPriority priority() {
			return priority;
		}

		private long acquireTimeMs() {
			return acquireTimeMs;
		}

		private long releaseTimeMs() {
			return releaseTimeMs;
		}

		private long queueWaitMs() {
			return queueWaitMs;
		}
	}

	public record OrderMeta(Long acquireTimeMs, Long releaseTimeMs, Long queueWaitMs, String orderPriority) {
	}

	public record QueueResult(QueueDecision decision, String reason) {
	}
}
