package lab.bank.core.ledger.application

/** 기간 안 모든 전표 줄의 차변 합·대변 합 (시산표). 복식부기라 늘 같아야 한다 */
data class DailyTotals(val debitSum: Long, val creditSum: Long)
