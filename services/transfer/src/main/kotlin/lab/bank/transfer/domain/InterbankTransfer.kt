package lab.bank.transfer.domain

import java.time.Instant
import java.util.UUID

class ExternalTransfer (
    var id: UUID? = null,
    var requesterId: UUID? = null,
    val idempotencyKey: IdempotencyKey,   // 고객이 보낸 멱등 키 (송금 ID와 다름, IdempotencyKey 주석). (요청자, 키)로 유일
    val fromAccountId: UUID,
    val toAccountId: String,
    val toBankCode: String,
    val amount: Amount,               // 원 단위, 0보다 큼 (타입이 보장)
    status: TransferStatus,
    failureCode: String?,
    coreEntryId: UUID?,
    val createdAt: Instant,
    updatedAt: Instant,
    // 낙관적 락 버전. 원래 저장 기술의 관심사지만, 트랜잭션을 나눠(처리중 커밋 → core 호출 → 결과 커밋) 읽고 쓰기 때문에
    // 버전을 도메인이 들고 다녀야 "그사이 누가 바꿨는지"를 저장할 때 잡을 수 있다 (8주 차 해소 스케줄러와 경합). 새 송금은 null
    val version: Long?,
) {
        var status: TransferStatus = status
            private set

        // 실패 사유: core가 준 에러 코드 그대로 (INSUFFICIENT_BALANCE, ACCOUNT_NOT_FOUND ...). FAILED일 때만 있다
        var failureCode: String? = failureCode
            private set

        // core 원장의 거래 ID. COMPLETED일 때만 있다. transfer ↔ core 대사(8주 차)의 연결 고리
        var coreEntryId: UUID? = coreEntryId
            private set

        var updatedAt: Instant = updatedAt
            private set

        init {
            require(fromAccountId != toAccountId) { "송금 간 계좌가 동일함" }
            // 금액 > 0은 Amount 타입이 보장한다
        }
}