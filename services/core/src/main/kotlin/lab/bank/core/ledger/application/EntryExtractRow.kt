package lab.bank.core.ledger.application

import java.time.Instant
import java.util.UUID

/**
 * 대사 추출용 전표 한 줄 (ADR-0006). 금액은 전표의 차변 합(= 대변 합). type은 문자열 (EntrySummary와 같은 이유: 도메인 enum을 밖에 안 내보낸다)
 */
data class EntryExtractRow(
    val entryId: UUID,
    val idempotencyKey: String,   // 송금이면 송금 ID, 취소면 "{송금ID}-cancel"
    val type: String,
    val amount: Long,
    val createdAt: Instant,
    val reversesEntryId: UUID?,   // 취소 전표만
)
