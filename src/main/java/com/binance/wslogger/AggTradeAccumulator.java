package com.binance.wslogger;

public class AggTradeAccumulator {

    private long tradeCount;
    private double takerBuyQty;
    private double takerSellQty;

    public synchronized void add(double quantity, boolean buyerIsMaker) {
        tradeCount++;
        if (buyerIsMaker) {
            takerSellQty += quantity;
        } else {
            takerBuyQty += quantity;
        }
    }

    public synchronized AggTradeSnapshot snapshotAndReset() {
        AggTradeSnapshot snapshot = new AggTradeSnapshot(tradeCount, takerBuyQty, takerSellQty);
        tradeCount = 0L;
        takerBuyQty = 0.0;
        takerSellQty = 0.0;
        return snapshot;
    }

    public record AggTradeSnapshot(long tradeCount, double takerBuyQty, double takerSellQty) {
    }
}
