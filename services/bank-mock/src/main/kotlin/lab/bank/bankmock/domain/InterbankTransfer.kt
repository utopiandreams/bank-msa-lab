package lab.bank.bankmock.domain

/**
 * 외부 결제망이 받은 이체 한 건과 그 결과.
 * 같은 키 재요청에는 이 기록을 그대로 돌려준다 (내용이 다르면 거절).
 */
data class InterbankTransfer(
    val idempotencyKey: String,
    val toBankCode: String,
    val toAccountNumber: String,
    val amount: Long,
    val outcome: InterbankOutcome,
    val rejectReason: RejectReason?,
) {
    /** 같은 키로 온 요청이 처음과 같은 내용인가 */
    fun sameRequestAs(toBankCode: String, toAccountNumber: String, amount: Long): Boolean =
        this.toBankCode == toBankCode && this.toAccountNumber == toAccountNumber && this.amount == amount
}
