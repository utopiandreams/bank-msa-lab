package lab.bank.reconciliation.application

import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** 인박스 한 줄: 추출 완료 알림을 받았다 */
data class ExtractArrival(
    val extractId: UUID,
    val source: String,
    val businessDate: LocalDate,
    val objectKey: String,
    val rowCount: Long,
    val amountSum: Long,
    val sha256: String,
    val debitSum: Long?,
    val creditSum: Long?,
    val extractedAt: Instant,
    val receivedAt: Instant,
)
