package com.binance.wslogger;

import java.util.concurrent.atomic.DoubleAdder;
import java.util.concurrent.atomic.LongAdder;

public class AggTradeAccumulator {

    private final LongAdder tradeCount = new LongAdder();
    private final DoubleAdder takerBuyQty = new DoubleAdder();
    private final DoubleAdder takerSellQty = new DoubleAdder();

    public void add(double quantity, boolean buyerIsMaker) {
        tradeCount.increment();
        if (buyerIsMaker) {
            takerSellQty.add(quantity);
        } else {
            takerBuyQty.add(quantity);
        }
    }

    public AggTradeSnapshot snapshotAndReset() {
        long trades = tradeCount.sumThenReset();
        double buys = takerBuyQty.sumThenReset();
        double sells = takerSellQty.sumThenReset();
        return new AggTradeSnapshot(trades, buys, sells);
    }

    public record AggTradeSnapshot(long tradeCount, double takerBuyQty, double takerSellQty) {
    }
}
