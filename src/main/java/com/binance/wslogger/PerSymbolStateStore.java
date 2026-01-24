package com.binance.wslogger;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public class PerSymbolStateStore {

    private static final double EPS = 1e-12;
    private static final DateTimeFormatter ISO_FORMATTER = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private final String symbol;
    private final int warmupSamples;
    private final int rollingShort;
    private final int rollingLong;
    private final ZoneId zoneId;

    private final AggTradeAccumulator aggTradeAccumulator = new AggTradeAccumulator();
    private final RollingWindow rollingWindow;

    private long seq;
    private long lastMarkTs;
    private long lastBookTs;
    private long lastAggTs;
    private double markPrice;
    private double bestBid;
    private double bestAsk;
    private double bidQty;
    private double askQty;

    public PerSymbolStateStore(String symbol, WsSnapshotLoggerProperties properties) {
        this.symbol = symbol;
        this.warmupSamples = properties.getWarmupSamples();
        this.rollingShort = properties.getRollingShort();
        this.rollingLong = properties.getRollingLong();
        this.zoneId = ZoneId.of("Europe/Istanbul");
        this.rollingWindow = new RollingWindow(rollingShort, rollingLong);
    }

    public String getSymbol() {
        return symbol;
    }

    public synchronized void updateMarkPrice(double price, long eventTime) {
        this.markPrice = price;
        this.lastMarkTs = eventTime;
    }

    public synchronized void updateBookTicker(double bid, double ask, double bidQty, double askQty, long eventTime) {
        this.bestBid = bid;
        this.bestAsk = ask;
        this.bidQty = bidQty;
        this.askQty = askQty;
        this.lastBookTs = eventTime;
    }

    public synchronized void updateAggTrade(double quantity, boolean buyerIsMaker, long eventTime) {
        aggTradeAccumulator.add(quantity, buyerIsMaker);
        this.lastAggTs = eventTime;
    }

    public synchronized SnapshotRecord buildSnapshot(long now, int logEverySec) {
        AggTradeAccumulator.AggTradeSnapshot aggSnapshot = aggTradeAccumulator.snapshotAndReset();
        long tradeCount3s = aggSnapshot.tradeCount();
        double takerBuyQty3s = aggSnapshot.takerBuyQty();
        double takerSellQty3s = aggSnapshot.takerSellQty();
        double takerDeltaQty3s = takerBuyQty3s - takerSellQty3s;
        double takerDeltaRatio3s = takerDeltaQty3s / (takerBuyQty3s + takerSellQty3s + EPS);

        double midPrice = (bestBid + bestAsk) / 2.0;
        double spread = bestAsk - bestBid;
        double spreadPct = spread / (midPrice + EPS);
        double imbalanceTop = (bidQty - askQty) / (bidQty + askQty + EPS);

        RollingMetrics metrics = rollingWindow.addSample(markPrice, takerDeltaRatio3s, takerBuyQty3s, takerSellQty3s);
        boolean ready = metrics.sampleCount >= warmupSamples;

        long lagMarkMs = lastMarkTs > 0 ? Math.max(0, now - lastMarkTs) : Long.MAX_VALUE;
        long lagBookMs = lastBookTs > 0 ? Math.max(0, now - lastBookTs) : Long.MAX_VALUE;
        long lagAggMs = lastAggTs > 0 ? Math.max(0, now - lastAggTs) : Long.MAX_VALUE;
        boolean stale = lagMarkMs > 5000 || lagBookMs > 8000 || lagAggMs > 8000;

        String isoTime = ISO_FORMATTER.format(Instant.ofEpochMilli(now).atZone(zoneId));

        return new SnapshotRecord(
                symbol,
                now,
                isoTime,
                ++seq,
                logEverySec,
                ready,
                lastMarkTs,
                lastBookTs,
                lastAggTs,
                lagMarkMs,
                lagBookMs,
                lagAggMs,
                stale,
                markPrice,
                bestBid,
                bestAsk,
                bidQty,
                askQty,
                midPrice,
                spread,
                spreadPct,
                imbalanceTop,
                tradeCount3s,
                takerBuyQty3s,
                takerSellQty3s,
                takerDeltaQty3s,
                takerDeltaRatio3s,
                metrics.ret1,
                metrics.ret20,
                metrics.sma20,
                metrics.volAbs20,
                metrics.volStd20,
                metrics.volSpike,
                metrics.rsi14,
                metrics.bbMid20,
                metrics.bbStd20,
                metrics.bbUpper20,
                metrics.bbLower20,
                metrics.bbPercentB20,
                metrics.takerDeltaRatio20,
                metrics.buySellImbalance20);
    }

    private static final class RollingWindow {

        private final int shortWindow;
        private final int capacity;
        private final double[] prices;
        private final double[] returns;
        private final double[] takerDeltaRatios;
        private final double[] buyQtys;
        private final double[] sellQtys;
        private int index = -1;
        private int count;

        private RollingWindow(int shortWindow, int capacity) {
            this.shortWindow = shortWindow;
            this.capacity = capacity;
            this.prices = new double[capacity];
            this.returns = new double[capacity];
            this.takerDeltaRatios = new double[capacity];
            this.buyQtys = new double[capacity];
            this.sellQtys = new double[capacity];
        }

        private RollingMetrics addSample(double price, double takerDeltaRatio, double buyQty, double sellQty) {
            double prevPrice = count > 0 ? prices[index] : price;
            double ret1 = prevPrice > 0 ? (price / prevPrice) - 1.0 : 0.0;

            index = (index + 1) % capacity;
            prices[index] = price;
            returns[index] = ret1;
            takerDeltaRatios[index] = takerDeltaRatio;
            buyQtys[index] = buyQty;
            sellQtys[index] = sellQty;
            if (count < capacity) {
                count++;
            }

            RollingMetrics metrics = new RollingMetrics();
            metrics.sampleCount = count;
            metrics.ret1 = ret1;
            metrics.ret20 = computeReturn(rollingShort());
            metrics.sma20 = computeSma(rollingShort());
            metrics.volAbs20 = computeVolAbs(rollingShort());
            metrics.volStd20 = computeVolStd(rollingShort());
            metrics.volSpike = computeVolSpike();
            metrics.rsi14 = computeRsi(14);
            metrics.bbMid20 = metrics.sma20;
            metrics.bbStd20 = computePriceStd(rollingShort());
            metrics.bbUpper20 = metrics.bbMid20 + 2.0 * metrics.bbStd20;
            metrics.bbLower20 = metrics.bbMid20 - 2.0 * metrics.bbStd20;
            metrics.bbPercentB20 = (price - metrics.bbLower20) / ((metrics.bbUpper20 - metrics.bbLower20) + EPS);
            metrics.takerDeltaRatio20 = computeAverage(takerDeltaRatios, rollingShort());
            metrics.buySellImbalance20 = computeBuySellImbalance(rollingShort());
            return metrics;
        }

        private int rollingShort() {
            return Math.min(shortWindow, count);
        }

        private double computeReturn(int lookback) {
            if (count <= lookback) {
                return 0.0;
            }
            double prev = priceAtOffset(lookback);
            double current = priceAtOffset(0);
            return prev > 0 ? (current / prev) - 1.0 : 0.0;
        }

        private double computeSma(int window) {
            if (window == 0) {
                return 0.0;
            }
            double sum = 0.0;
            for (int i = 0; i < window; i++) {
                sum += priceAtOffset(i);
            }
            return sum / window;
        }

        private double computeVolAbs(int window) {
            if (window == 0) {
                return 0.0;
            }
            double sum = 0.0;
            for (int i = 0; i < window; i++) {
                sum += Math.abs(returnAtOffset(i));
            }
            return sum / window;
        }

        private double computeVolStd(int window) {
            if (window == 0) {
                return 0.0;
            }
            double mean = 0.0;
            for (int i = 0; i < window; i++) {
                mean += returnAtOffset(i);
            }
            mean /= window;
            double variance = 0.0;
            for (int i = 0; i < window; i++) {
                double diff = returnAtOffset(i) - mean;
                variance += diff * diff;
            }
            return Math.sqrt(variance / window);
        }

        private double computeVolSpike() {
            int longWindow = Math.min(capacity, count);
            if (longWindow == 0) {
                return 0.0;
            }
            double longAvg = computeVolAbs(longWindow);
            double shortAvg = computeVolAbs(rollingShort());
            return shortAvg / (longAvg + EPS);
        }

        private double computeRsi(int period) {
            // Simple RSI using average gains/losses over the last N log-sample price changes.
            if (count < period + 1) {
                return 0.0;
            }
            double gain = 0.0;
            double loss = 0.0;
            for (int i = 0; i < period; i++) {
                double change = priceAtOffset(i) - priceAtOffset(i + 1);
                if (change > 0) {
                    gain += change;
                } else {
                    loss -= change;
                }
            }
            double avgGain = gain / period;
            double avgLoss = loss / period;
            if (avgLoss == 0.0) {
                return 100.0;
            }
            double rs = avgGain / avgLoss;
            return 100.0 - (100.0 / (1.0 + rs));
        }

        private double computePriceStd(int window) {
            if (window == 0) {
                return 0.0;
            }
            double mean = computeSma(window);
            double variance = 0.0;
            for (int i = 0; i < window; i++) {
                double diff = priceAtOffset(i) - mean;
                variance += diff * diff;
            }
            return Math.sqrt(variance / window);
        }

        private double computeAverage(double[] values, int window) {
            if (window == 0) {
                return 0.0;
            }
            double sum = 0.0;
            for (int i = 0; i < window; i++) {
                sum += valueAtOffset(values, i);
            }
            return sum / window;
        }

        private double computeBuySellImbalance(int window) {
            if (window == 0) {
                return 0.0;
            }
            double buySum = 0.0;
            double sellSum = 0.0;
            for (int i = 0; i < window; i++) {
                buySum += valueAtOffset(buyQtys, i);
                sellSum += valueAtOffset(sellQtys, i);
            }
            return (buySum - sellSum) / (buySum + sellSum + EPS);
        }

        private double priceAtOffset(int offset) {
            return valueAtOffset(prices, offset);
        }

        private double returnAtOffset(int offset) {
            return valueAtOffset(returns, offset);
        }

        private double valueAtOffset(double[] buffer, int offset) {
            int idx = index - offset;
            while (idx < 0) {
                idx += capacity;
            }
            return buffer[idx % capacity];
        }
    }

    private static final class RollingMetrics {
        private int sampleCount;
        private double ret1;
        private double ret20;
        private double sma20;
        private double volAbs20;
        private double volStd20;
        private double volSpike;
        private double rsi14;
        private double bbMid20;
        private double bbStd20;
        private double bbUpper20;
        private double bbLower20;
        private double bbPercentB20;
        private double takerDeltaRatio20;
        private double buySellImbalance20;
    }
}
