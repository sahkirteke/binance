package com.binance.strategy;

import java.math.BigDecimal;

public final class StrategyLogV1 {

	private StrategyLogV1() {
	}

	public record DecisionLogDto(
			String symbol,
			Long closeTime,
			Double close,
			Double bfr1m,
			Double bfr5m,
			Double adx5m,
			boolean adxGate,
			String adxGateReason,
			boolean adx5mReady,
			Integer scoreLong,
			Integer scoreShort,
			Integer score1m,
			Integer score5m,
			Integer hamScore,
			Integer adxBonus,
			Double adjScore,
			CtiDirection bias,
			CtiDirection rec,
			CtiDirection recommendationRaw,
			CtiDirection recommendationUsed,
			boolean insufficientData,
			String insufficientReason,
			Integer streak,
			Integer confirmBars,
			CtiDirection confirmedRec,
			String recReason,
			CtiDirection recPending,
			Long recFirstSeenAt,
			BigDecimal recFirstSeenPrice,
			String action,
			String positionSide,
			BigDecimal positionQty,
			Integer openOrders,
			Long cMissed,
			Long cConfirm,
			Long cFlip) {
	}

	public record ConfirmHitLogDto(
			String symbol,
			CtiDirection confirmedRec,
			Long firstSeenAt,
			BigDecimal firstPrice,
			Long hitAt,
			BigDecimal hitPrice,
			Integer barsToConfirm,
			Integer confirmBars,
			Double bfr1m,
			Double bfr5m,
			Double adx5m,
			Long cMissed,
			Long cConfirm,
			Long cFlip) {
	}

	public record MissedMoveLogDto(
			String symbol,
			CtiDirection pending,
			Long firstSeenAt,
			BigDecimal firstPrice,
			Long nowAt,
			BigDecimal nowPrice,
			Integer streakBeforeReset,
			Integer confirmBars,
			Double bfr1m,
			Double bfr5m,
			Double adx5m,
			Long cMissed,
			Long cConfirm,
			Long cFlip) {
	}

	public record FlipLogDto(
			String symbol,
			String from,
			String to,
			Long at,
			BigDecimal price,
			Double adjScore,
			Integer scoreLong,
			Integer scoreShort,
			CtiDirection rec,
			CtiDirection confirmedRec,
			Double bfr1m,
			Double bfr5m,
			Double adx5m,
			String action,
			String positionBefore,
			BigDecimal qtyBefore,
			boolean orderPlaced,
			String orderId,
			Long cMissed,
			Long cConfirm,
			Long cFlip) {
	}

	public record SummaryLogDto(
			Integer symbols,
			Long cFlip,
			Long cConfirm,
			Long cMissed,
			Double missRate,
			Double confirmRate,
			String topMissed) {
	}

	/*
	Example decision:
	EVENT=DECISION strategy=CTI_SCORE symbol=BTCUSDT tf=1m closeTime=1700000000000 close=43000 bfr1m=0.12 bfr5m=-0.05 adx5m=25 adxGate=true adxGateReason=ADX5M>20 adx5mReady=true scoreLong=1 scoreShort=1 adjScore=0 bias=NEUTRAL rec=NONE streak=0 confirmBars=2 confirmedRec=NONE recReason=SCORE_RULES recPending=NONE recFirstSeenAt=0 recFirstSeenPrice=0 action=HOLD positionSide=FLAT positionQty=0 openOrders=0 cMissed=0 cConfirm=0 cFlip=0
	Example flip:
	EVENT=FLIP strategy=CTI_SCORE symbol=BTCUSDT tf=1m from=FLAT to=LONG at=1700000000000 price=43000 adjScore=2 scoreLong=2 scoreShort=0 rec=LONG confirmedRec=LONG bfr1m=0.12 bfr5m=0.2 adx5m=25 action=FLIP_TO_LONG positionBefore=FLAT qtyBefore=0 orderPlaced=false orderId=NONE cMissed=0 cConfirm=1 cFlip=1
	Example confirm hit:
	EVENT=CONFIRM_HIT strategy=CTI_SCORE symbol=BTCUSDT tf=1m confirmedRec=LONG firstSeenAt=1700000000000 firstPrice=43000 hitAt=1700000060000 hitPrice=43050 barsToConfirm=2 confirmBars=2 bfr1m=0.12 bfr5m=0.2 adx5m=25 action=HOLD cMissed=0 cConfirm=1 cFlip=0
	Example missed move:
	EVENT=MISSED_MOVE strategy=CTI_SCORE symbol=BTCUSDT tf=1m pending=LONG firstSeenAt=1700000000000 firstPrice=43000 nowAt=1700000060000 nowPrice=42980 streakBeforeReset=1 confirmBars=2 bfr1m=0.12 bfr5m=-0.05 adx5m=18 action=RESET_PENDING cMissed=1 cConfirm=0 cFlip=0
	*/
}
