package com.binance.wslogger;

public class SnapshotRecord {

    private final String symbol;
    private final long t;
    private final String isoTime;
    private final long seq;
    private final int logEverySec;
    private final boolean ready;
    private final long lastMarkTs;
    private final long lastBookTs;
    private final long lastAggTs;
    private final long lagMarkMs;
    private final long lagBookMs;
    private final long lagAggMs;
    private final boolean stale;
    private final double markPrice;
    private final double bestBid;
    private final double bestAsk;
    private final double bidQty;
    private final double askQty;
    private final double midPrice;
    private final double spread;
    private final double spreadPct;
    private final double imbalanceTop;
    private final long tradeCount3s;
    private final double takerBuyQty3s;
    private final double takerSellQty3s;
    private final double takerDeltaQty3s;
    private final double takerDeltaRatio3s;
    private final double ret1;
    private final double ret20;
    private final double sma20;
    private final double volAbs20;
    private final double volStd20;
    private final double volSpike;
    private final double rsi14;
    private final double bbMid20;
    private final double bbStd20;
    private final double bbUpper20;
    private final double bbLower20;
    private final double bbPercentB20;
    private final double takerDeltaRatio20;
    private final double buySellImbalance20;

    public SnapshotRecord(
            String symbol,
            long t,
            String isoTime,
            long seq,
            int logEverySec,
            boolean ready,
            long lastMarkTs,
            long lastBookTs,
            long lastAggTs,
            long lagMarkMs,
            long lagBookMs,
            long lagAggMs,
            boolean stale,
            double markPrice,
            double bestBid,
            double bestAsk,
            double bidQty,
            double askQty,
            double midPrice,
            double spread,
            double spreadPct,
            double imbalanceTop,
            long tradeCount3s,
            double takerBuyQty3s,
            double takerSellQty3s,
            double takerDeltaQty3s,
            double takerDeltaRatio3s,
            double ret1,
            double ret20,
            double sma20,
            double volAbs20,
            double volStd20,
            double volSpike,
            double rsi14,
            double bbMid20,
            double bbStd20,
            double bbUpper20,
            double bbLower20,
            double bbPercentB20,
            double takerDeltaRatio20,
            double buySellImbalance20) {
        this.symbol = symbol;
        this.t = t;
        this.isoTime = isoTime;
        this.seq = seq;
        this.logEverySec = logEverySec;
        this.ready = ready;
        this.lastMarkTs = lastMarkTs;
        this.lastBookTs = lastBookTs;
        this.lastAggTs = lastAggTs;
        this.lagMarkMs = lagMarkMs;
        this.lagBookMs = lagBookMs;
        this.lagAggMs = lagAggMs;
        this.stale = stale;
        this.markPrice = markPrice;
        this.bestBid = bestBid;
        this.bestAsk = bestAsk;
        this.bidQty = bidQty;
        this.askQty = askQty;
        this.midPrice = midPrice;
        this.spread = spread;
        this.spreadPct = spreadPct;
        this.imbalanceTop = imbalanceTop;
        this.tradeCount3s = tradeCount3s;
        this.takerBuyQty3s = takerBuyQty3s;
        this.takerSellQty3s = takerSellQty3s;
        this.takerDeltaQty3s = takerDeltaQty3s;
        this.takerDeltaRatio3s = takerDeltaRatio3s;
        this.ret1 = ret1;
        this.ret20 = ret20;
        this.sma20 = sma20;
        this.volAbs20 = volAbs20;
        this.volStd20 = volStd20;
        this.volSpike = volSpike;
        this.rsi14 = rsi14;
        this.bbMid20 = bbMid20;
        this.bbStd20 = bbStd20;
        this.bbUpper20 = bbUpper20;
        this.bbLower20 = bbLower20;
        this.bbPercentB20 = bbPercentB20;
        this.takerDeltaRatio20 = takerDeltaRatio20;
        this.buySellImbalance20 = buySellImbalance20;
    }

    public String getSymbol() {
        return symbol;
    }

    public long getT() {
        return t;
    }

    public String getIsoTime() {
        return isoTime;
    }

    public long getSeq() {
        return seq;
    }

    public int getLogEverySec() {
        return logEverySec;
    }

    public boolean isReady() {
        return ready;
    }

    public long getLastMarkTs() {
        return lastMarkTs;
    }

    public long getLastBookTs() {
        return lastBookTs;
    }

    public long getLastAggTs() {
        return lastAggTs;
    }

    public long getLagMarkMs() {
        return lagMarkMs;
    }

    public long getLagBookMs() {
        return lagBookMs;
    }

    public long getLagAggMs() {
        return lagAggMs;
    }

    public boolean isStale() {
        return stale;
    }

    public double getMarkPrice() {
        return markPrice;
    }

    public double getBestBid() {
        return bestBid;
    }

    public double getBestAsk() {
        return bestAsk;
    }

    public double getBidQty() {
        return bidQty;
    }

    public double getAskQty() {
        return askQty;
    }

    public double getMidPrice() {
        return midPrice;
    }

    public double getSpread() {
        return spread;
    }

    public double getSpreadPct() {
        return spreadPct;
    }

    public double getImbalanceTop() {
        return imbalanceTop;
    }

    public long getTradeCount3s() {
        return tradeCount3s;
    }

    public double getTakerBuyQty3s() {
        return takerBuyQty3s;
    }

    public double getTakerSellQty3s() {
        return takerSellQty3s;
    }

    public double getTakerDeltaQty3s() {
        return takerDeltaQty3s;
    }

    public double getTakerDeltaRatio3s() {
        return takerDeltaRatio3s;
    }

    public double getRet1() {
        return ret1;
    }

    public double getRet20() {
        return ret20;
    }

    public double getSma20() {
        return sma20;
    }

    public double getVolAbs20() {
        return volAbs20;
    }

    public double getVolStd20() {
        return volStd20;
    }

    public double getVolSpike() {
        return volSpike;
    }

    public double getRsi14() {
        return rsi14;
    }

    public double getBbMid20() {
        return bbMid20;
    }

    public double getBbStd20() {
        return bbStd20;
    }

    public double getBbUpper20() {
        return bbUpper20;
    }

    public double getBbLower20() {
        return bbLower20;
    }

    public double getBbPercentB20() {
        return bbPercentB20;
    }

    public double getTakerDeltaRatio20() {
        return takerDeltaRatio20;
    }

    public double getBuySellImbalance20() {
        return buySellImbalance20;
    }
}
