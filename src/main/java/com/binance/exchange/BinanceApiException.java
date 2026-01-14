package com.binance.exchange;

public class BinanceApiException extends RuntimeException {

	private final Integer code;

	public BinanceApiException(Integer code, String message) {
		super(message);
		this.code = code;
	}

	public Integer code() {
		return code;
	}
}
