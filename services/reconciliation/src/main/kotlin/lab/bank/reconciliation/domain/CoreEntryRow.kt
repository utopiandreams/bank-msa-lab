package lab.bank.reconciliation.domain

import java.time.Instant
import java.util.UUID

/** core 추출 파일 한 줄 (core CoreExtractCsv와 같은 칸) */
data class CoreEntryRow(
    val entryId: UUID,
    val idempotencyKey: String,
    val type: String,
    val amount: Long,
    val createdAt: Instant,
    val reversesEntryId: UUID?,
) {
    /** 이 전표의 송금 ID: 키가 송금 ID, 취소는 "{송금ID}-cancel". UUID가 아니면 null (송금을 거치지 않은 전표, 예: 7주 차 k6 직접 호출) */
    val transferId: UUID?
        get() = runCatching { UUID.fromString(idempotencyKey.removeSuffix(CANCEL_SUFFIX)) }.getOrNull()

    companion object {
        const val CANCEL_SUFFIX = "-cancel"
    }
}
