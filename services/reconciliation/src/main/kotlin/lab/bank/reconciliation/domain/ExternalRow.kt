package lab.bank.reconciliation.domain

import java.time.Instant
import java.util.UUID

/** 외부 정산 파일 한 줄을 우리 말로 번역한 것 (ACL, 수신 작업이 SettlementFile 형식에서 바꾼다) */
data class ExternalRow(
    val key: String,              // 외부 거래키 = 우리가 보낸 Idempotency-Key = 송금 ID
    val toBankCode: String,
    val toAccountNumber: String,
    val amount: Long,
    val accepted: Boolean,        // A = 입금, R = 거절
    val rejectReason: String?,
    val processedAt: Instant,
) {
    val transferId: UUID? get() = runCatching { UUID.fromString(key) }.getOrNull()
}
