package lab.bank.common.event

import java.time.Instant
import java.util.UUID

/**
 * 송금이 끝 상태(COMPLETED·FAILED·CANCELLED)로 바뀌었다 (ADR-0003, CANCELLED는 ADR-0004). transfer가 발행, notification이 구독.
 *
 * 공유 클래스로 둔 것은 모노레포라서 고른 타협이다. 실무(서비스별 레포)라면 서비스마다 자기 클래스 + JSON 계약이 낫다.
 * 그래서 계약은 "클래스"가 아니라 "JSON 모양"으로 생각한다:
 *   - 필드를 **추가**하는 건 괜찮다 (소비자는 모르는 필드를 무시하고 읽는다)
 *   - 필드를 **지우거나 이름·의미를 바꾸면** schemaVersion을 올리고 소비자 먼저 배포한다 (pitfalls 18: expand → contract)
 *
 * 민감 정보: 계좌 ID는 내부 UUID라 넣는다. 잔액은 넣지 않는다 (이벤트는 여러 소비자에게 퍼지고 오래 보관된다).
 */
data class TransferFinishedEvent(
    val eventId: UUID,              // 이벤트마다 고유. 소비자의 중복 판별 키 (CLAUDE.md 7장: 모든 컨슈머는 멱등)
    val schemaVersion: Int,
    val occurredAt: Instant,        // 끝 상태가 된 시각
    val transferId: UUID,           // 파티션 키 (ADR-0003 결정 3)
    val requesterId: String,        // 누구에게 알릴지
    val fromAccountId: UUID,
    // 받는 쪽: 당행이면 toAccountId, 타행이면 toBankCode + toAccountNumber (ADR-0004).
    // 2026-11-19 계약 넓힘: toAccountId 필수 → 선택. 필수를 선택으로 바꾸는 건 소비자가 그 필드를 필수로 읽고 있으면 깨지는 변경이다
    // (null을 못 받아 역직렬화 실패 → 깨진 메시지로 건너뜀). 지금 소비자(notification)는 이 필드를 읽지 않아 안전. 새 필드 추가는 늘 안전
    val toAccountId: UUID?,
    val amount: Long,               // 원 단위
    val status: String,             // COMPLETED | FAILED | CANCELLED (enum이 아니라 문자열: 생산자가 값을 추가해도 소비자가 안 깨지게)
    val failureCode: String?,       // FAILED·CANCELLED일 때 (INSUFFICIENT_BALANCE, ACCOUNT_NOT_FOUND 등)
    val toBankCode: String? = null,
    val toAccountNumber: String? = null,
) {
    companion object {
        const val TOPIC = "transfer-events"
        const val SCHEMA_VERSION = 1
        const val COMPLETED = "COMPLETED"
        const val FAILED = "FAILED"
        // 타행 이체가 외부에서 거절돼 출금을 돌려줬다 (보상 완료). 고객은 잔액이 줄었다 늘어나는 걸 봤으므로 알려야 한다
        const val CANCELLED = "CANCELLED"
    }
}
