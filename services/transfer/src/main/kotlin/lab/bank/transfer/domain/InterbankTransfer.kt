package lab.bank.transfer.domain

import lab.bank.transfer.domain.InterbankTransferStatus.CANCELLED
import lab.bank.transfer.domain.InterbankTransferStatus.COMPLETED
import lab.bank.transfer.domain.InterbankTransferStatus.WITHDRAW_CANCEL_REQUESTED
import lab.bank.transfer.domain.InterbankTransferStatus.EXTERNAL_BANK_REQUESTED
import lab.bank.transfer.domain.InterbankTransferStatus.FAILED
import lab.bank.transfer.domain.InterbankTransferStatus.WITHDRAWN
import lab.bank.transfer.domain.InterbankTransferStatus.WITHDRAW_REQUESTED
import java.time.Instant
import java.util.UUID

class InterbankTransfer (
    val id: UUID,
    val requesterId: UserId,
    val idempotencyKey: IdempotencyKey,   // 고객이 보낸 멱등 키 (송금 ID와 다름, IdempotencyKey 주석). (요청자, 키)로 유일
    val fromAccountId: UUID,
    val toAccountId: String,
    val toBankCode: BankCode,
    val amount: Amount,               // 원 단위, 0보다 큼 (타입이 보장)
    status: InterbankTransferStatus,
    failureCode: String?,
    coreEntryId: UUID?,
    cancelEntryId: UUID?,
    externalRejectReason: String?,
    val createdAt: Instant,
    updatedAt: Instant,
    // 낙관적 락 버전. 원래 저장 기술의 관심사지만, 트랜잭션을 나눠(처리중 커밋 → core 호출 → 결과 커밋) 읽고 쓰기 때문에
    // 버전을 도메인이 들고 다녀야 "그사이 누가 바꿨는지"를 저장할 때 잡을 수 있다 (8주 차 해소 스케줄러와 경합). 새 송금은 null
    val version: Long?,
) {
    var status: InterbankTransferStatus = status
        private set

    // 출금 실패 사유: core가 준 에러 코드 그대로 (INSUFFICIENT_BALANCE, ACCOUNT_NOT_FOUND ...). FAILED일 때만 있다
    var failureCode: String? = failureCode
        private set

    // core 원장의 타행 출금 전표 ID. WITHDRAWN 이후 계속 있다. transfer ↔ core 대사(8주 차)의 연결 고리
    var coreEntryId: UUID? = coreEntryId
        private set

    // core 원장의 출금 취소(역분개) 전표 ID. CANCELLED일 때만 있다. 대사에서 "출금 = 취소"를 맞춰 볼 때 쓴다
    var cancelEntryId: UUID? = cancelEntryId
        private set

    // 외부 은행이 거절한 사유 그대로 (ACCOUNT_NOT_FOUND, RECEIVING_BANK_REJECTED). 출금 실패 사유(failureCode)와 출처가 달라 따로 둔다
    // WITHDRAW_CANCEL_REQUESTED 이후 계속 있다 (보상이 끝나 CANCELLED가 돼도 "왜 돌려줬나"가 남아야 한다)
    var externalRejectReason: String? = externalRejectReason
        private set

    var updatedAt: Instant = updatedAt
        private set

    init {
        // 은행 코드 형식은 BankCode 타입이 보장한다
        require(!toBankCode.isOurs) { "우리 은행 코드면 타행 송금이 아니다 (당행 송금으로)" }
        require(toAccountId.isNotBlank()) { "받는 계좌번호가 비어 있음" }
        // 금액 > 0은 Amount 타입이 보장한다
    }

    // 같은 멱등 키로 온 요청이 이 송금과 같은 내용인가. 요청자·키는 이미 (요청자, 키)로 찾았으니 비교하지 않는다
    // 받는 쪽은 은행 코드 + 계좌번호 둘 다 비교: 계좌번호가 같아도 은행이 다르면 다른 사람이다
    fun isSameRequestAs(fromAccountId: UUID, toBankCode: BankCode, toAccountId: String, amount: Amount): Boolean =
        this.fromAccountId == fromAccountId &&
            this.toBankCode == toBankCode &&
            this.toAccountId == toAccountId &&
            this.amount == amount

    fun withdrawRequested() = transitTo(WITHDRAW_REQUESTED)

    fun withdrawn(coreEntryId: UUID) {
        transitTo(WITHDRAWN)
        this.coreEntryId = coreEntryId
    }

    fun withdrawRejected(failureCode: String) {
        transitTo(FAILED)
        this.failureCode = failureCode
    }

    fun externalBankRequested() = transitTo(EXTERNAL_BANK_REQUESTED)

    fun completed() = transitTo(COMPLETED)

    fun externalBankRejected(reason: String) {
        transitTo(WITHDRAW_CANCEL_REQUESTED)
        this.externalRejectReason = reason
    }

    fun cancelled(cancelEntryId: UUID) {
        transitTo(CANCELLED)
        this.cancelEntryId = cancelEntryId
    }

    private fun transitTo(to: InterbankTransferStatus) {
        check(status.canTransitTo(to)) { "잘못된 상태 전이: $status => $to" }
        status = to
        updatedAt = Instant.now()
    }

    companion object {
        fun request(requesterId: UserId, idempotencyKey: IdempotencyKey, fromAccountId: UUID, toBankCode: BankCode, toAccountId: String, amount: Amount): InterbankTransfer {
            val now = Instant.now()
            return InterbankTransfer(
                id = UUID.randomUUID(),
                requesterId = requesterId,
                idempotencyKey = idempotencyKey,
                fromAccountId = fromAccountId,
                toAccountId = toAccountId,
                toBankCode = toBankCode,
                amount = amount,
                status = InterbankTransferStatus.REQUESTED,
                failureCode = null,
                coreEntryId = null,
                cancelEntryId = null,
                externalRejectReason = null,
                createdAt = now,
                updatedAt = now,
                version = null,
            )
        }
    }
}
