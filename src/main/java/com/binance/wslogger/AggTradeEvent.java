package com.binance.wslogger;

public record AggTradeEvent(
        String symbol,
        double quantity,
        boolean buyerIsMaker,
        long eventTimeMs) implements MarketEvent {
}
