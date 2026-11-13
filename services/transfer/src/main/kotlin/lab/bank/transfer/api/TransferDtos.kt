package lab.bank.transfer.api

import lab.bank.transfer.domain.Transfer
import lab.bank.transfer.domain.TransferStatus
import java.time.Instant
import java.util.UUID

// DTO는 원시 타입: API는 외부와의 약속이라 도메인 모델이 바뀌어도 흔들리지 않게 (core와 같은 원칙)

data class CreateTransferRequest(
    val fromAccountId: UUID,
    val toAccountId: UUID,
    val amount: Long,
)

data class TransferResponse(
    val transferId: UUID,
    val fromAccountId: UUID,
    val toAccountId: UUID,
    val amount: Long,
    val status: TransferStatus,
    val failureCode: String?,   // FAILED일 때만
    val coreEntryId: UUID?,     // COMPLETED일 때만
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    companion object {
        fun from(t: Transfer) = TransferResponse(
            transferId = t.id,
            fromAccountId = t.fromAccountId,
            toAccountId = t.toAccountId,
            amount = t.amount.value,
            status = t.status,
            failureCode = t.failureCode,
            coreEntryId = t.coreEntryId,
            createdAt = t.createdAt,
            updatedAt = t.updatedAt,
        )
    }
}
