package lab.bank.transfer.domain

import java.time.Instant
import java.util.UUID

/**
 * 송금 (도메인 모델). transfer는 헥사고날이라 JPA를 모른다 → 저장은 infrastructure의 TransferJpaEntity + TransferMapper가 맡는다.
 *
 * id = 송금 ID. core에 보내는 Idempotency-Key로도 쓴다 ("구간마다 자기 멱등 키": 고객 키를 core까지 넘기지 않는다).
 *
 * 생성자는 "저장된 송금을 복원"하는 용도 (매퍼·테스트). 새 송금 만들기와 상태 변경은 아래 도메인 메서드로만 한다.
 * 상태 필드는 private set: 밖에서 status = FAILED처럼 대입할 수 없다.
 */
class Transfer(
    val id: UUID,
    val fromAccountId: UUID,
    val toAccountId: UUID,
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

    fun startProcessing() = transitTo(TransferStatus.PROCESSING)
    fun completeProcessing(coreEntryId: UUID) = transitTo(TransferStatus.COMPLETED, coreEntryId)
    fun failedProcessing(failureCode: String) = transitTo(TransferStatus.FAILED, null, failureCode)
    fun markUnknown() = transitTo(TransferStatus.UNKNOWN)

    private fun transitTo(to: TransferStatus, coreEntryId: UUID? = null, failureCode: String? = null) {
        check (status.canTransitTo(to)) { "잘못된 상태 전이 ${this.status.name} => ${to.name}" }
        this.coreEntryId = coreEntryId
        this.failureCode = failureCode
        status = to
        updatedAt = Instant.now()
    }

    companion object {
        fun request(fromAccountId: UUID, toAccountId: UUID, amount: Amount): Transfer {
            val now = Instant.now()
            return Transfer(
                id = UUID.randomUUID(),
                fromAccountId = fromAccountId,
                toAccountId = toAccountId,
                amount = amount,
                status = TransferStatus.REQUESTED,
                createdAt = now,
                updatedAt = now,
                failureCode = null,
                coreEntryId = null,
                version = null,
            )
        }

    }
}
