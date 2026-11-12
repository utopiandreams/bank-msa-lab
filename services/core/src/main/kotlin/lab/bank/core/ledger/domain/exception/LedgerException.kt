package lab.bank.core.ledger.domain.exception

// ledger 모듈의 업무 예외 (account 모듈의 AccountException과 같은 구조)
sealed class LedgerException(
    val code: String,
    message: String,
) : RuntimeException(message)

// 같은 멱등 키로 내용이 다른 요청이 왔다: 클라이언트 버그이거나 키 재사용. 성공으로 응답하면 돈이 틀어진다
class IdempotencyConflictException(idempotencyKey: String) :
    LedgerException("IDEMPOTENCY_CONFLICT", "같은 멱등 키로 다른 내용의 요청이 왔습니다: $idempotencyKey")
