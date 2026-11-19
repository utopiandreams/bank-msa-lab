package lab.bank.transfer.api

import lab.bank.transfer.domain.InterbankTransfer
import lab.bank.transfer.domain.InterbankTransferStatus
import lab.bank.transfer.domain.Transfer
import java.time.Instant
import java.util.UUID

// DTO는 원시 타입: API는 외부와의 약속이라 도메인 모델이 바뀌어도 흔들리지 않게 (core와 같은 원칙)

/**
 * 송금 요청 (당행·타행 하나의 API, ADR-0004 결정 6). toBankCode로 갈린다:
 *   당행: toBankCode 없음(또는 우리 은행 코드) + toAccountId (우리 은행 계좌 ID)
 *   타행: toBankCode(다른 은행 3자리) + toAccountNumber (상대 은행 계좌번호)
 * 어느 쪽 칸이 필요한지가 toBankCode에 따라 달라서 둘 다 nullable로 받고 컨트롤러가 조합을 검사한다 (틀리면 400)
 */
data class CreateTransferRequest(
    val fromAccountId: UUID,
    val amount: Long,
    val toAccountId: UUID? = null,
    val toBankCode: String? = null,
    val toAccountNumber: String? = null,
)

/**
 * 송금 응답 (당행·타행 같은 모양). 받는 쪽 칸은 종류에 따라 채워진다.
 * status는 문자열: 당행·타행 상태 enum이 달라서 (클라이언트는 COMPLETED·FAILED·CANCELLED만 끝으로 보면 된다)
 */
data class TransferResponse(
    val transferId: UUID,
    val kind: String,            // DOMESTIC | INTERBANK
    val fromAccountId: UUID,
    val toAccountId: UUID?,      // 당행만
    val toBankCode: String?,     // 타행만
    val toAccountNumber: String?,// 타행만
    val amount: Long,
    val status: String,
    val failureCode: String?,    // 왜 실패·취소됐나 (FAILED: 출금 거절 사유, CANCELLED: 외부 거절 사유)
    val coreEntryId: UUID?,      // core 원장 전표 (당행: 송금 전표, 타행: 출금 전표)
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    companion object {
        fun from(t: Transfer) = TransferResponse(
            transferId = t.id,
            kind = DOMESTIC,
            fromAccountId = t.fromAccountId,
            toAccountId = t.toAccountId,
            toBankCode = null,
            toAccountNumber = null,
            amount = t.amount.value,
            status = t.status.name,
            failureCode = t.failureCode,
            coreEntryId = t.coreEntryId,
            createdAt = t.createdAt,
            updatedAt = t.updatedAt,
        )

        fun from(t: InterbankTransfer) = TransferResponse(
            transferId = t.id,
            kind = INTERBANK,
            fromAccountId = t.fromAccountId,
            toAccountId = null,
            toBankCode = t.toBankCode.value,
            toAccountNumber = t.toAccountId,
            amount = t.amount.value,
            status = t.status.name,
            // 고객에게 보이는 "왜"는 칸 하나 (이벤트 계약과 같은 규칙)
            failureCode = if (t.status == InterbankTransferStatus.CANCELLED) t.externalRejectReason else t.failureCode,
            coreEntryId = t.coreEntryId,
            createdAt = t.createdAt,
            updatedAt = t.updatedAt,
        )

        private const val DOMESTIC = "DOMESTIC"
        private const val INTERBANK = "INTERBANK"
    }
}
