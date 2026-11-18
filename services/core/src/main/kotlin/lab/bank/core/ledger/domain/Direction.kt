package lab.bank.core.ledger.domain

// 차변(왼쪽) / 대변(오른쪽)
enum class Direction {
    DEBIT,
    CREDIT;

    // 역분개(취소 전표)에서 쓴다
    fun opposite(): Direction = if (this == DEBIT) CREDIT else DEBIT
}
