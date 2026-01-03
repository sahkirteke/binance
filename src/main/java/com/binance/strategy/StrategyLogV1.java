package com.binance.strategy;

import java.math.BigDecimal;
public final class StrategyLogV1 {

	private StrategyLogV1() {
	}

	public static String buildDecisionLine(DecisionLogDto dto) {
		StringBuilder builder = new StringBuilder(512);
		builder.append("EVENT=DECISION")
				.append(" strategy=CTI_SCORE")
				.append(" symbol=").append(na(dto.symbol()))
				.append(" tf=1m")
				.append(" closeTime=").append(nl(dto.closeTime()))
				.append(" close=").append(num(dto.close()))
				.append(" cti1m=").append(num(dto.cti1m()))
				.append(" cti5m=").append(num(dto.cti5m()))
				.append(" adx5m=").append(num(dto.adx5m()))
				.append(" adxGate=").append(dto.adxGate())
				.append(" scoreLong=").append(na(dto.scoreLong()))
				.append(" scoreShort=").append(na(dto.scoreShort()))
				.append(" adjScore=").append(num(dto.adjScore()))
				.append(" bias=").append(ctiDir(dto.bias()))
				.append(" rec=").append(recDir(dto.rec()))
				.append(" streak=").append(na(dto.streak()))
				.append(" confirmBars=").append(na(dto.confirmBars()))
				.append(" confirmedRec=").append(recDir(dto.confirmedRec()))
				.append(" recPending=").append(recDir(dto.recPending()))
				.append(" recFirstSeenAt=").append(nl(dto.recFirstSeenAt()))
				.append(" recFirstSeenPrice=").append(num(dto.recFirstSeenPrice()))
				.append(" action=").append(na(dto.action()))
				.append(" positionSide=").append(na(dto.positionSide()))
				.append(" positionQty=").append(num(dto.positionQty()))
				.append(" openOrders=").append(na(dto.openOrders()))
				.append(" cMissed=").append(nl(dto.cMissed()))
				.append(" cConfirm=").append(nl(dto.cConfirm()))
				.append(" cFlip=").append(nl(dto.cFlip()));
		return builder.toString();
	}

	public static String buildConfirmHitLine(ConfirmHitLogDto dto) {
		StringBuilder builder = new StringBuilder(384);
		builder.append("EVENT=CONFIRM_HIT")
				.append(" strategy=CTI_SCORE")
				.append(" symbol=").append(na(dto.symbol()))
				.append(" tf=1m")
				.append(" confirmedRec=").append(recDir(dto.confirmedRec()))
				.append(" firstSeenAt=").append(nl(dto.firstSeenAt()))
				.append(" firstPrice=").append(num(dto.firstPrice()))
				.append(" hitAt=").append(nl(dto.hitAt()))
				.append(" hitPrice=").append(num(dto.hitPrice()))
				.append(" barsToConfirm=").append(na(dto.barsToConfirm()))
				.append(" confirmBars=").append(na(dto.confirmBars()))
				.append(" cti1m=").append(num(dto.cti1m()))
				.append(" cti5m=").append(num(dto.cti5m()))
				.append(" adx5m=").append(num(dto.adx5m()))
				.append(" action=HOLD")
				.append(" cMissed=").append(nl(dto.cMissed()))
				.append(" cConfirm=").append(nl(dto.cConfirm()))
				.append(" cFlip=").append(nl(dto.cFlip()));
		return builder.toString();
	}

	public static String buildMissedMoveLine(MissedMoveLogDto dto) {
		StringBuilder builder = new StringBuilder(384);
		builder.append("EVENT=MISSED_MOVE")
				.append(" strategy=CTI_SCORE")
				.append(" symbol=").append(na(dto.symbol()))
				.append(" tf=1m")
				.append(" pending=").append(recDir(dto.pending()))
				.append(" firstSeenAt=").append(nl(dto.firstSeenAt()))
				.append(" firstPrice=").append(num(dto.firstPrice()))
				.append(" nowAt=").append(nl(dto.nowAt()))
				.append(" nowPrice=").append(num(dto.nowPrice()))
				.append(" streakBeforeReset=").append(na(dto.streakBeforeReset()))
				.append(" confirmBars=").append(na(dto.confirmBars()))
				.append(" cti1m=").append(num(dto.cti1m()))
				.append(" cti5m=").append(num(dto.cti5m()))
				.append(" adx5m=").append(num(dto.adx5m()))
				.append(" action=RESET_PENDING")
				.append(" cMissed=").append(nl(dto.cMissed()))
				.append(" cConfirm=").append(nl(dto.cConfirm()))
				.append(" cFlip=").append(nl(dto.cFlip()));
		return builder.toString();
	}

	public static String buildFlipLine(FlipLogDto dto) {
		StringBuilder builder = new StringBuilder(512);
		builder.append("EVENT=FLIP")
				.append(" strategy=CTI_SCORE")
				.append(" symbol=").append(na(dto.symbol()))
				.append(" tf=1m")
				.append(" from=").append(na(dto.from()))
				.append(" to=").append(na(dto.to()))
				.append(" at=").append(nl(dto.at()))
				.append(" price=").append(num(dto.price()))
				.append(" adjScore=").append(num(dto.adjScore()))
				.append(" scoreLong=").append(na(dto.scoreLong()))
				.append(" scoreShort=").append(na(dto.scoreShort()))
				.append(" rec=").append(recDir(dto.rec()))
				.append(" confirmedRec=").append(recDir(dto.confirmedRec()))
				.append(" cti1m=").append(num(dto.cti1m()))
				.append(" cti5m=").append(num(dto.cti5m()))
				.append(" adx5m=").append(num(dto.adx5m()))
				.append(" action=").append(na(dto.action()))
				.append(" positionBefore=").append(na(dto.positionBefore()))
				.append(" qtyBefore=").append(num(dto.qtyBefore()))
				.append(" orderPlaced=").append(dto.orderPlaced())
				.append(" orderId=").append(na(dto.orderId()))
				.append(" cMissed=").append(nl(dto.cMissed()))
				.append(" cConfirm=").append(nl(dto.cConfirm()))
				.append(" cFlip=").append(nl(dto.cFlip()));
		return builder.toString();
	}

	public static String buildSummaryLine(SummaryLogDto dto) {
		StringBuilder builder = new StringBuilder(384);
		builder.append("EVENT=SUMMARY")
				.append(" strategy=CTI_SCORE")
				.append(" window=15m")
				.append(" symbols=").append(na(dto.symbols()))
				.append(" cFlip=").append(nl(dto.cFlip()))
				.append(" cConfirm=").append(nl(dto.cConfirm()))
				.append(" cMissed=").append(nl(dto.cMissed()))
				.append(" missRate=").append(num(dto.missRate()))
				.append(" confirmRate=").append(num(dto.confirmRate()))
				.append(" topMissed=").append(na(dto.topMissed()));
		return builder.toString();
	}

	private static String na(Object value) {
		if (value == null) {
			return "0";
		}
		String text = value.toString();
		return text.isBlank() ? "0" : text;
	}

	private static String nl(Long value) {
		return value == null ? "0" : value.toString();
	}

	private static String num(BigDecimal value) {
		return value == null ? "0" : value.stripTrailingZeros().toPlainString();
	}

	private static String num(Double value) {
		if (value == null || value.isNaN() || value.isInfinite()) {
			return "0";
		}
		return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
	}

	private static String ctiDir(CtiDirection direction) {
		if (direction == null) {
			return "NEUTRAL";
		}
		return direction.name();
	}

	private static String recDir(CtiDirection direction) {
		if (direction == null || direction == CtiDirection.NEUTRAL) {
			return "NONE";
		}
		return direction.name();
	}

	public record DecisionLogDto(
			String symbol,
			Long closeTime,
			Double close,
			Double cti1m,
			Double cti5m,
			Double adx5m,
			boolean adxGate,
			Integer scoreLong,
			Integer scoreShort,
			Double adjScore,
			CtiDirection bias,
			CtiDirection rec,
			Integer streak,
			Integer confirmBars,
			CtiDirection confirmedRec,
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
			Double cti1m,
			Double cti5m,
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
			Double cti1m,
			Double cti5m,
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
			Double cti1m,
			Double cti5m,
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
	EVENT=DECISION strategy=CTI_SCORE symbol=BTCUSDT tf=1m closeTime=1700000000000 close=43000 cti1m=0.12 cti5m=-0.05 adx5m=25 adxGate=true scoreLong=1 scoreShort=1 adjScore=0 bias=NEUTRAL rec=NONE streak=0 confirmBars=2 confirmedRec=NONE recPending=NONE recFirstSeenAt=0 recFirstSeenPrice=0 action=HOLD positionSide=FLAT positionQty=0 openOrders=0 cMissed=0 cConfirm=0 cFlip=0
	Example flip:
	EVENT=FLIP strategy=CTI_SCORE symbol=BTCUSDT tf=1m from=FLAT to=LONG at=1700000000000 price=43000 adjScore=2 scoreLong=2 scoreShort=0 rec=LONG confirmedRec=LONG cti1m=0.12 cti5m=0.2 adx5m=25 action=FLIP_TO_LONG positionBefore=FLAT qtyBefore=0 orderPlaced=false orderId=NONE cMissed=0 cConfirm=1 cFlip=1
	Example confirm hit:
	EVENT=CONFIRM_HIT strategy=CTI_SCORE symbol=BTCUSDT tf=1m confirmedRec=LONG firstSeenAt=1700000000000 firstPrice=43000 hitAt=1700000060000 hitPrice=43050 barsToConfirm=2 confirmBars=2 cti1m=0.12 cti5m=0.2 adx5m=25 action=HOLD cMissed=0 cConfirm=1 cFlip=0
	Example missed move:
	EVENT=MISSED_MOVE strategy=CTI_SCORE symbol=BTCUSDT tf=1m pending=LONG firstSeenAt=1700000000000 firstPrice=43000 nowAt=1700000060000 nowPrice=42980 streakBeforeReset=1 confirmBars=2 cti1m=0.12 cti5m=-0.05 adx5m=18 action=RESET_PENDING cMissed=1 cConfirm=0 cFlip=0
	*/
}
