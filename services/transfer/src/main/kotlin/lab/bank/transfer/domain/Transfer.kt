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
    val amount: Long,                 // 원 단위
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

    // ───────────── TODO(개발자): 도메인 메서드 ─────────────
    // 1. 새 송금 만들기: companion object의 팩토리 (예: request(from, to, amount)) → REQUESTED, id = UUID.randomUUID()
    //    불변식: 금액 > 0, from != to (어디서 검사할지: init? 팩토리?)
    // 2. 상태 전이: startProcessing(), complete(coreEntryId), fail(failureCode), markUnknown()
    //    - 허용되지 않은 전이는 예외 (예: COMPLETED → FAILED, REQUESTED → COMPLETED)
    //    - 끝 상태(COMPLETED, FAILED)는 바뀌지 않는다
    //    - 전이할 때 updatedAt 갱신, complete는 coreEntryId, fail은 failureCode를 함께 채운다
    //    - UNKNOWN → COMPLETED/FAILED는 8주 차(상태 조회로 해소)에 필요하다. 지금 열어 둘지 정하기
}
