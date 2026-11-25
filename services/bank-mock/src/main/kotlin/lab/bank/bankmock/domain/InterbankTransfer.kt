package lab.bank.bankmock.domain

import java.time.Instant

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
    // 판정한 시각. 외부 기관의 "처리일"이 여기서 정해진다 → 정산 파일은 이 시각의 날짜(Asia/Seoul)로 묶인다
    val processedAt: Instant,
) {
    /** 같은 키로 온 요청이 처음과 같은 내용인가 */
    fun sameRequestAs(toBankCode: String, toAccountNumber: String, amount: Long): Boolean =
        this.toBankCode == toBankCode && this.toAccountNumber == toAccountNumber && this.amount == amount
}
