package com.binance.strategy;

import java.math.BigDecimal;

public final class StrategyLogLineBuilder {

	private StrategyLogLineBuilder() {
	}

	public static String buildDecisionLine(StrategyLogV1.DecisionLogDto dto) {
		StringBuilder builder = new StringBuilder(512);
		builder.append("EVENT=DECISION")
				.append(" strategy=CTI_SCORE")
				.append(" symbol=").append(na(dto.symbol()))
				.append(" tf=1m")
				.append(" closeTime=").append(nl(dto.closeTime()))
				.append(" close=").append(num(dto.close()))
				.append(" bfr1m=").append(num(dto.bfr1m()))
				.append(" bfrPrev=").append(num(dto.bfrPrev()))
				.append(" bfr5m=").append(num(dto.bfr5m()))
				.append(" adx5m=").append(num(dto.adx5m()))
				.append(" cti5mReady=").append(dto.cti5mReady())
				.append(" cti5mBarsSeen=").append(na(dto.cti5mBarsSeen()))
				.append(" cti5mPeriod=").append(na(dto.cti5mPeriod()))
				.append(" adx5mReady=").append(dto.adx5mReady())
				.append(" adx5mBarsSeen=").append(na(dto.adx5mBarsSeen()))
				.append(" adx5mPeriod=").append(na(dto.adx5mPeriod()))
				.append(" insufficientData=").append(dto.insufficientData())
				.append(" insufficientReason=").append(na(dto.insufficientReason()))
				.append(" score1m=").append(na(dto.score1m()))
				.append(" score5m=").append(na(dto.score5m()))
				.append(" hamScore=").append(na(dto.hamScore()))
				.append(" adxBonus=").append(na(dto.adxBonus()))
				.append(" scoreLong=").append(na(dto.scoreLong()))
				.append(" scoreShort=").append(na(dto.scoreShort()))
				.append(" adjScore=").append(num(dto.adjScore()))
				.append(" bias=").append(ctiDir(dto.bias()))
				.append(" recommendationRaw=").append(recDir(dto.recommendationRaw()))
				.append(" recommendationUsed=").append(recDir(dto.recommendationUsed()))
				.append(" adxGate=").append(dto.adxGate())
				.append(" adxGateReason=").append(na(dto.adxGateReason()))
				.append(" streak=").append(na(dto.streak()))
				.append(" confirmBars=").append(na(dto.confirmBars()))
				.append(" confirmedRec=").append(recDir(dto.confirmedRec()))
				.append(" recReason=").append(na(dto.recReason()))
				.append(" recPending=").append(recDir(dto.recPending()))
				.append(" recFirstSeenAt=").append(nl(dto.recFirstSeenAt()))
				.append(" recFirstSeenPrice=").append(num(dto.recFirstSeenPrice()))
				.append(" trend=").append(recDir(dto.trend()))
				.append(" confirmCounter=").append(na(dto.confirmCounter()))
				.append(" action=").append(na(dto.action()))
				.append(" decisionActionReason=").append(na(dto.decisionActionReason()))
				.append(" enableOrders=").append(dto.enableOrders())
				.append(" resolvedQty=").append(num(dto.resolvedQty()))
				.append(" entryPrice=").append(num(dto.entryPrice()))
				.append(" estimatedPnlPct=").append(num(dto.estimatedPnlPct()))
				.append(" positionSide=").append(na(dto.positionSide()))
				.append(" positionQty=").append(num(dto.positionQty()))
				.append(" qtyStep=").append(num(dto.qtyStep()))
				.append(" notionalUsdt=").append(num(dto.notionalUsdt()))
				.append(" maxPositionUsdt=").append(num(dto.maxPositionUsdt()))
				.append(" hedgeMode=").append(na(dto.hedgeMode()))
				.append(" exchangePosSide=").append(na(dto.exchangePosSide()))
				.append(" exchangePosQty=").append(num(dto.exchangePosQty()))
				.append(" stateDesync=").append(na(dto.stateDesync()))
				.append(" decisionBlockReason=").append(na(dto.decisionBlockReason()))
				.append(" openOrders=").append(na(dto.openOrders()))
				.append(" cMissed=").append(nl(dto.cMissed()))
				.append(" cConfirm=").append(nl(dto.cConfirm()))
				.append(" cFlip=").append(nl(dto.cFlip()));
		return builder.toString();
	}

	public static String buildConfirmHitLine(StrategyLogV1.ConfirmHitLogDto dto) {
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
				.append(" bfr1m=").append(num(dto.bfr1m()))
				.append(" bfr5m=").append(num(dto.bfr5m()))
				.append(" adx5m=").append(num(dto.adx5m()))
				.append(" action=HOLD")
				.append(" cMissed=").append(nl(dto.cMissed()))
				.append(" cConfirm=").append(nl(dto.cConfirm()))
				.append(" cFlip=").append(nl(dto.cFlip()));
		return builder.toString();
	}

	public static String buildMissedMoveLine(StrategyLogV1.MissedMoveLogDto dto) {
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
				.append(" bfr1m=").append(num(dto.bfr1m()))
				.append(" bfr5m=").append(num(dto.bfr5m()))
				.append(" adx5m=").append(num(dto.adx5m()))
				.append(" action=RESET_PENDING")
				.append(" cMissed=").append(nl(dto.cMissed()))
				.append(" cConfirm=").append(nl(dto.cConfirm()))
				.append(" cFlip=").append(nl(dto.cFlip()));
		return builder.toString();
	}

	public static String buildFlipLine(StrategyLogV1.FlipLogDto dto) {
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
				.append(" bfr1m=").append(num(dto.bfr1m()))
				.append(" bfr5m=").append(num(dto.bfr5m()))
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

	public static String buildSummaryLine(StrategyLogV1.SummaryLogDto dto) {
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

	public static String buildPositionSyncLine(StrategyLogV1.PositionSyncLogDto dto) {
		StringBuilder builder = new StringBuilder(256);
		builder.append("EVENT=POSITION_SYNC")
				.append(" symbol=").append(na(dto.symbol()))
				.append(" exchangeSide=").append(na(dto.exchangeSide()))
				.append(" exchangeQty=").append(num(dto.exchangeQty()))
				.append(" localSide=").append(na(dto.localSide()))
				.append(" desync=").append(dto.desync());
		return builder.toString();
	}

	private static String na(Object value) {
		if (value == null) {
			return "NA";
		}
		String text = value.toString();
		return text.isBlank() ? "NA" : text;
	}

	private static String nl(Long value) {
		return value == null ? "NA" : value.toString();
	}

	private static String num(BigDecimal value) {
		return value == null ? "NA" : value.stripTrailingZeros().toPlainString();
	}

	private static String num(Double value) {
		if (value == null || value.isNaN() || value.isInfinite()) {
			return "NA";
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
}
