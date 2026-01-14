package com.binance.exchange;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import com.binance.config.BinanceProperties;
import com.binance.exchange.dto.OrderResponse;
import reactor.core.publisher.Mono;

@Component
public class BinanceFuturesOrderClient {

	private static final long RECV_WINDOW_MS = 10_000L;
	private static final Pattern BINANCE_CODE_PATTERN = Pattern.compile("\"code\"\\s*:\\s*(-?\\d+)");
	private final WebClient binanceWebClient;
	private final BinanceProperties properties;
	private final SignatureUtil signatureUtil;
	private final TimeSyncServices timeSyncService;

	public BinanceFuturesOrderClient(WebClient binanceWebClient, BinanceProperties properties, SignatureUtil signatureUtil,
			TimeSyncServices timeSyncService) {
		this.binanceWebClient = binanceWebClient;
		this.properties = properties;
		this.signatureUtil = signatureUtil;
		this.timeSyncService = timeSyncService;
	}

	public Mono<OrderResponse> placeMarketOrder(String symbol, String side, BigDecimal quantity, String positionSide) {
		return placeMarketOrder(symbol, side, quantity, positionSide, null);
	}

	public Mono<OrderResponse> placeMarketOrder(String symbol, String side, BigDecimal quantity, String positionSide,
			String clientOrderId) {
		if (properties.apiKey() == null || properties.apiKey().isBlank()
				|| properties.secretKey() == null || properties.secretKey().isBlank()) {
			return Mono.error(new IllegalStateException(
					"Binance API key/secret is not configured. Set BINANCE_API_KEY and BINANCE_SECRET_KEY."));
		}
		return withTimestampRetry(() -> {
			long timestamp = timeSyncService.currentTimestampMillis();
			String resolvedClientOrderId = clientOrderId == null || clientOrderId.isBlank()
					? UUID.randomUUID().toString()
					: clientOrderId;
			String payload = String.format(
					"symbol=%s&side=%s&type=MARKET&quantity=%s&recvWindow=%d&timestamp=%d&newClientOrderId=%s",
					symbol,
					side,
					quantity.toPlainString(),
					RECV_WINDOW_MS,
					timestamp,
					resolvedClientOrderId);
			if (positionSide != null && !positionSide.isBlank()) {
				payload = payload + "&positionSide=" + positionSide;
			}
			String signedPayload = signPayload(payload);
			return binanceWebClient
					.post()
					.uri(uriBuilder -> uriBuilder
							.path("/fapi/v1/order")
							.query(signedPayload)
							.build())
					.header(HttpHeaders.CONTENT_TYPE, "application/x-www-form-urlencoded")
					.header("X-MBX-APIKEY", properties.apiKey())
					.retrieve()
					.onStatus(status -> status.isError(), response -> response
							.bodyToMono(String.class)
							.defaultIfEmpty("<empty>")
							.flatMap(body -> Mono.error(toBinanceException("Binance order failed",
									response.statusCode().value(), body))))
					.bodyToMono(OrderResponse.class);
		});
	}

	public Mono<OrderResponse> placeReduceOnlyMarketOrder(String symbol, String side, BigDecimal quantity,
			String positionSide) {
		return placeMarketOrderWithFlags(symbol, side, quantity, positionSide, true, null);
	}

	public Mono<OrderResponse> placeReduceOnlyMarketOrder(String symbol, String side, BigDecimal quantity,
			String positionSide, String clientOrderId) {
		return placeMarketOrderWithFlags(symbol, side, quantity, positionSide, true, clientOrderId);
	}

	public Mono<OrderResponse> placeStopMarketOrder(String symbol, String side, BigDecimal quantity,
			BigDecimal stopPrice, boolean reduceOnly, String positionSide) {
		return placeTriggeredOrder(symbol, side, quantity, stopPrice, "STOP_MARKET", reduceOnly, positionSide, null);
	}

	public Mono<OrderResponse> placeTakeProfitMarketOrder(String symbol, String side, BigDecimal quantity,
			BigDecimal stopPrice, boolean reduceOnly, String positionSide) {
		return placeTriggeredOrder(symbol, side, quantity, stopPrice, "TAKE_PROFIT_MARKET", reduceOnly, positionSide,
				null);
	}

	public Mono<Void> cancelAllOpenOrders(String symbol) {
		if (properties.apiKey() == null || properties.apiKey().isBlank()
				|| properties.secretKey() == null || properties.secretKey().isBlank()) {
			return Mono.error(new IllegalStateException(
					"Binance API key/secret is not configured. Set BINANCE_API_KEY and BINANCE_SECRET_KEY."));
		}
		return withTimestampRetry(() -> {
			long timestamp = timeSyncService.currentTimestampMillis();
			String payload = String.format("symbol=%s&recvWindow=%d&timestamp=%d",
					symbol,
					RECV_WINDOW_MS,
					timestamp);
			String signedPayload = signPayload(payload);

			return binanceWebClient
					.delete()
					.uri(uriBuilder -> uriBuilder
							.path("/fapi/v1/allOpenOrders")
							.query(signedPayload)
							.build())
					.header(HttpHeaders.CONTENT_TYPE, "application/x-www-form-urlencoded")
					.header("X-MBX-APIKEY", properties.apiKey())
					.retrieve()
					.onStatus(status -> status.isError(), response -> response
							.bodyToMono(String.class)
							.defaultIfEmpty("<empty>")
							.flatMap(body -> Mono.error(toBinanceException("Binance cancel failed",
									response.statusCode().value(), body))))
					.bodyToMono(Void.class);
		});
	}

	public Mono<OrderResponse> fetchOrder(String symbol, Long orderId) {
		if (properties.apiKey() == null || properties.apiKey().isBlank()
				|| properties.secretKey() == null || properties.secretKey().isBlank()) {
			return Mono.error(new IllegalStateException(
					"Binance API key/secret is not configured. Set BINANCE_API_KEY and BINANCE_SECRET_KEY."));
		}
		if (orderId == null) {
			return Mono.error(new IllegalArgumentException("orderId is required"));
		}
		return withTimestampRetry(() -> {
			long timestamp = timeSyncService.currentTimestampMillis();
			String payload = String.format("symbol=%s&orderId=%d&recvWindow=%d&timestamp=%d",
					symbol,
					orderId,
					RECV_WINDOW_MS,
					timestamp);
			String signedPayload = signPayload(payload);

			return binanceWebClient
					.get()
					.uri(uriBuilder -> uriBuilder
							.path("/fapi/v1/order")
							.query(signedPayload)
							.build())
					.header(HttpHeaders.CONTENT_TYPE, "application/x-www-form-urlencoded")
					.header("X-MBX-APIKEY", properties.apiKey())
					.retrieve()
					.onStatus(status -> status.isError(), response -> response
							.bodyToMono(String.class)
							.defaultIfEmpty("<empty>")
							.flatMap(body -> Mono.error(toBinanceException("Binance order fetch failed",
									response.statusCode().value(), body))))
					.bodyToMono(OrderResponse.class);
		});
	}

	public Mono<Boolean> fetchHedgeModeEnabled() {
		if (properties.apiKey() == null || properties.apiKey().isBlank()
				|| properties.secretKey() == null || properties.secretKey().isBlank()) {
			return Mono.error(new IllegalStateException(
					"Binance API key/secret is not configured. Set BINANCE_API_KEY and BINANCE_SECRET_KEY."));
		}
		return withTimestampRetry(() -> {
			long timestamp = timeSyncService.currentTimestampMillis();
			String payload = String.format("recvWindow=%d&timestamp=%d", RECV_WINDOW_MS, timestamp);
			String signedPayload = signPayload(payload);

			return binanceWebClient
					.get()
					.uri(uriBuilder -> uriBuilder
							.path("/fapi/v1/positionSide/dual")
							.query(signedPayload)
							.build())
					.header(HttpHeaders.CONTENT_TYPE, "application/x-www-form-urlencoded")
					.header("X-MBX-APIKEY", properties.apiKey())
					.retrieve()
					.onStatus(status -> status.isError(), response -> response
							.bodyToMono(String.class)
							.defaultIfEmpty("<empty>")
							.flatMap(body -> Mono.error(toBinanceException("Binance position mode fetch failed",
									response.statusCode().value(), body))))
					.bodyToMono(PositionModeResponse.class)
					.map(PositionModeResponse::dualSidePosition);
		});
	}

	public Mono<ExchangePosition> fetchPosition(String symbol) {
		if (properties.apiKey() == null || properties.apiKey().isBlank()
				|| properties.secretKey() == null || properties.secretKey().isBlank()) {
			return Mono.error(new IllegalStateException(
					"Binance API key/secret is not configured. Set BINANCE_API_KEY and BINANCE_SECRET_KEY."));
		}
		return withTimestampRetry(() -> {
			long timestamp = timeSyncService.currentTimestampMillis();
			String payload = String.format("recvWindow=%d&timestamp=%d", RECV_WINDOW_MS, timestamp);
			String signedPayload = signPayload(payload);

			return binanceWebClient
					.get()
					.uri(uriBuilder -> uriBuilder
							.path("/fapi/v2/positionRisk")
							.query(signedPayload)
							.build())
					.header(HttpHeaders.CONTENT_TYPE, "application/x-www-form-urlencoded")
					.header("X-MBX-APIKEY", properties.apiKey())
					.retrieve()
					.onStatus(status -> status.isError(), response -> response
							.bodyToMono(String.class)
							.defaultIfEmpty("<empty>")
							.flatMap(body -> Mono.error(toBinanceException("Binance position fetch failed",
									response.statusCode().value(), body))))
					.bodyToFlux(PositionRiskResponse.class)
					.filter(position -> symbol.equalsIgnoreCase(position.symbol()))
					.next()
					.map(position -> new ExchangePosition(
							position.symbol(),
							position.positionAmt(),
							position.entryPrice(),
							position.positionSide()));
		});
	}

	public Mono<ExchangeInfoResponse> fetchExchangeInfo() {
		return binanceWebClient
				.get()
				.uri(uriBuilder -> uriBuilder
						.path("/fapi/v1/exchangeInfo")
						.build())
				.retrieve()
				.onStatus(status -> status.isError(), response -> response
						.bodyToMono(String.class)
						.defaultIfEmpty("<empty>")
						.flatMap(body -> Mono.error(new IllegalStateException(
								"Binance exchange info failed with status=" + response.statusCode().value()
										+ ", body=" + body))))
				.bodyToMono(ExchangeInfoResponse.class);
	}

	public Mono<SymbolFilters> fetchSymbolFilters(String symbol) {
		return fetchExchangeInfo()
				.flatMap(response -> response.symbols().stream()
						.filter(info -> symbol.equalsIgnoreCase(info.symbol()))
						.findFirst()
						.map(info -> Mono.just(resolveSymbolFilters(info)))
						.orElseGet(() -> Mono.error(new IllegalArgumentException("Symbol not found: " + symbol))));
	}

	public record ExchangePosition(
			String symbol,
			BigDecimal positionAmt,
			BigDecimal entryPrice,
			String positionSide) {
	}

	public record SymbolFilters(
			BigDecimal minQty,
			BigDecimal minNotional,
			BigDecimal stepSize,
			BigDecimal tickSize) {
	}

	public record OpenOrder(
			String symbol,
			long orderId,
			String clientOrderId,
			String status,
			String side,
			BigDecimal origQty,
			BigDecimal executedQty,
			long updateTime,
			boolean reduceOnly) {
	}

	private record PositionRiskResponse(
			String symbol,
			BigDecimal positionAmt,
			BigDecimal entryPrice,
			String positionSide) {
	}

	private record PositionModeResponse(boolean dualSidePosition) {}

	private record ListenKeyResponse(String listenKey) {}

	public record ExchangeInfoResponse(
			List<SymbolInfo> symbols) {
	}

	public record SymbolInfo(
			String symbol,
			List<ExchangeFilter> filters) {
	}

	public record ExchangeFilter(
			String filterType,
			BigDecimal minQty,
			BigDecimal minNotional,
			BigDecimal notional,
			BigDecimal stepSize,
			BigDecimal tickSize) {
	}

	public static SymbolFilters resolveSymbolFilters(SymbolInfo info) {
		BigDecimal minQty = null;
		BigDecimal minNotional = null;
		BigDecimal stepSize = null;
		BigDecimal tickSize = null;
		if (info.filters() != null) {
			for (ExchangeFilter filter : info.filters()) {
				if ("LOT_SIZE".equalsIgnoreCase(filter.filterType())) {
					minQty = filter.minQty();
					stepSize = filter.stepSize();
				}
				if ("MIN_NOTIONAL".equalsIgnoreCase(filter.filterType())
						|| "NOTIONAL".equalsIgnoreCase(filter.filterType())) {
					if (filter.minNotional() != null) {
						minNotional = filter.minNotional();
					} else {
						minNotional = filter.notional();
					}
				}
				if ("PRICE_FILTER".equalsIgnoreCase(filter.filterType())) {
					tickSize = filter.tickSize();
				}
			}
		}
		return new SymbolFilters(minQty, minNotional, stepSize, tickSize);
	}

	private Mono<OrderResponse> placeMarketOrderWithFlags(String symbol, String side, BigDecimal quantity,
			String positionSide, boolean reduceOnly, String clientOrderId) {
		if (properties.apiKey() == null || properties.apiKey().isBlank()
				|| properties.secretKey() == null || properties.secretKey().isBlank()) {
			return Mono.error(new IllegalStateException(
					"Binance API key/secret is not configured. Set BINANCE_API_KEY and BINANCE_SECRET_KEY."));
		}
		return executeOrder(() -> {
			long timestamp = timeSyncService.currentTimestampMillis();
			String resolvedClientOrderId = clientOrderId == null || clientOrderId.isBlank()
					? UUID.randomUUID().toString()
					: clientOrderId;
			String payload = String.format(
					"symbol=%s&side=%s&type=MARKET&quantity=%s&recvWindow=%d&timestamp=%d&newClientOrderId=%s",
					symbol,
					side,
					quantity.toPlainString(),
					RECV_WINDOW_MS,
					timestamp,
					resolvedClientOrderId);
			if (reduceOnly) {
				payload = payload + "&reduceOnly=true";
			}
			if (positionSide != null && !positionSide.isBlank()) {
				payload = payload + "&positionSide=" + positionSide;
			}
			return payload;
		});
	}

	private Mono<OrderResponse> placeTriggeredOrder(String symbol, String side, BigDecimal quantity,
			BigDecimal stopPrice, String type, boolean reduceOnly, String positionSide, String clientOrderId) {
		if (properties.apiKey() == null || properties.apiKey().isBlank()
				|| properties.secretKey() == null || properties.secretKey().isBlank()) {
			return Mono.error(new IllegalStateException(
					"Binance API key/secret is not configured. Set BINANCE_API_KEY and BINANCE_SECRET_KEY."));
		}
		return executeOrder(() -> {
			long timestamp = timeSyncService.currentTimestampMillis();
			String resolvedClientOrderId = clientOrderId == null || clientOrderId.isBlank()
					? UUID.randomUUID().toString()
					: clientOrderId;
			String payload = String.format(
					"symbol=%s&side=%s&type=%s&quantity=%s&stopPrice=%s&recvWindow=%d&timestamp=%d&newClientOrderId=%s",
					symbol,
					side,
					type,
					quantity.toPlainString(),
					stopPrice.toPlainString(),
					RECV_WINDOW_MS,
					timestamp,
					resolvedClientOrderId);
			if (reduceOnly) {
				payload = payload + "&reduceOnly=true";
			}
			if (positionSide != null && !positionSide.isBlank()) {
				payload = payload + "&positionSide=" + positionSide;
			}
			return payload;
		});
	}

	private Mono<OrderResponse> executeOrder(Supplier<String> payloadSupplier) {
		return withTimestampRetry(() -> {
			String signedPayload = signPayload(payloadSupplier.get());
			return binanceWebClient
					.post()
					.uri(uriBuilder -> uriBuilder
							.path("/fapi/v1/order")
							.query(signedPayload)
							.build())
					.header(HttpHeaders.CONTENT_TYPE, "application/x-www-form-urlencoded")
					.header("X-MBX-APIKEY", properties.apiKey())
					.retrieve()
					.onStatus(status -> status.isError(), response -> response
							.bodyToMono(String.class)
							.defaultIfEmpty("<empty>")
							.flatMap(body -> Mono.error(toBinanceException("Binance order failed",
									response.statusCode().value(), body))))
					.bodyToMono(OrderResponse.class);
		});
	}

	public Mono<String> startUserDataStream() {
		if (properties.apiKey() == null || properties.apiKey().isBlank()) {
			return Mono.error(new IllegalStateException("Binance API key is not configured."));
		}
		return binanceWebClient
				.post()
				.uri(uriBuilder -> uriBuilder
						.path("/fapi/v1/listenKey")
						.build())
				.header("X-MBX-APIKEY", properties.apiKey())
				.retrieve()
				.bodyToMono(ListenKeyResponse.class)
				.map(ListenKeyResponse::listenKey);
	}

	public Mono<Void> keepAliveUserDataStream(String listenKey) {
		if (properties.apiKey() == null || properties.apiKey().isBlank()) {
			return Mono.error(new IllegalStateException("Binance API key is not configured."));
		}
		return binanceWebClient
				.put()
				.uri(uriBuilder -> uriBuilder
						.path("/fapi/v1/listenKey")
						.queryParam("listenKey", listenKey)
						.build())
				.header("X-MBX-APIKEY", properties.apiKey())
				.retrieve()
				.bodyToMono(Void.class);
	}

	public Mono<Void> closeUserDataStream(String listenKey) {
		if (properties.apiKey() == null || properties.apiKey().isBlank()) {
			return Mono.error(new IllegalStateException("Binance API key is not configured."));
		}
		return binanceWebClient
				.delete()
				.uri(uriBuilder -> uriBuilder
						.path("/fapi/v1/listenKey")
						.queryParam("listenKey", listenKey)
						.build())
				.header("X-MBX-APIKEY", properties.apiKey())
				.retrieve()
				.bodyToMono(Void.class);
	}

	public Mono<Map<Long, OpenOrder>> fetchOpenOrders(String symbol) {
		if (properties.apiKey() == null || properties.apiKey().isBlank()
				|| properties.secretKey() == null || properties.secretKey().isBlank()) {
			return Mono.error(new IllegalStateException(
					"Binance API key/secret is not configured. Set BINANCE_API_KEY and BINANCE_SECRET_KEY."));
		}
		return withTimestampRetry(() -> {
			long timestamp = timeSyncService.currentTimestampMillis();
			String payload = String.format("symbol=%s&recvWindow=%d&timestamp=%d",
					symbol,
					RECV_WINDOW_MS,
					timestamp);
			String signedPayload = signPayload(payload);
			return binanceWebClient
					.get()
					.uri(uriBuilder -> uriBuilder
							.path("/fapi/v1/openOrders")
							.query(signedPayload)
							.build())
					.header(HttpHeaders.CONTENT_TYPE, "application/x-www-form-urlencoded")
					.header("X-MBX-APIKEY", properties.apiKey())
					.retrieve()
					.onStatus(status -> status.isError(), response -> response
							.bodyToMono(String.class)
							.defaultIfEmpty("<empty>")
							.flatMap(body -> Mono.error(toBinanceException("Binance open orders failed",
									response.statusCode().value(), body))))
					.bodyToFlux(OpenOrder.class)
					.collectMap(OpenOrder::orderId);
		});
	}

	private String signPayload(String payload) {
		String signature = signatureUtil.sign(payload, properties.secretKey());
		return payload + "&signature=" + signature;
	}

	private <T> Mono<T> withTimestampRetry(Supplier<Mono<T>> requestSupplier) {
		return requestSupplier.get()
				.onErrorResume(error -> {
					if (!isTimestampError(error)) {
						return Mono.error(error);
					}
					return timeSyncService.syncNow()
							.then(requestSupplier.get());
				});
	}

	private boolean isTimestampError(Throwable error) {
		if (error instanceof BinanceApiException exception) {
			return exception.code() != null && exception.code() == -1021;
		}
		return false;
	}

	private BinanceApiException toBinanceException(String prefix, int status, String body) {
		return new BinanceApiException(extractCode(body),
				prefix + " with status=" + status + ", body=" + body);
	}

	private Integer extractCode(String body) {
		if (body == null) {
			return null;
		}
		Matcher matcher = BINANCE_CODE_PATTERN.matcher(body);
		if (!matcher.find()) {
			return null;
		}
		try {
			return Integer.parseInt(matcher.group(1));
		} catch (NumberFormatException ignored) {
			return null;
		}
	}
}
