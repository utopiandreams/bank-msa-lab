package lab.bank.reconciliation.domain

import java.time.Instant
import java.util.UUID

/** transfer 추출 파일 한 줄 (transfer TransferExtractCsv와 같은 칸). 대사 서비스 쪽 모델 = 번역된 사본 (ACL) */
data class TransferRow(
    val transferId: UUID,
    val kind: String,                     // DOMESTIC | INTERBANK
    val amount: Long,
    val status: String,
    val createdAt: Instant,
    val updatedAt: Instant,
    val toBankCode: String?,
    val toAccountNumber: String?,
    val externalRejectReason: String?,
)
