package lab.bank.core.ledger.domain.exception

// ledger 모듈의 업무 예외 (account 모듈의 AccountException과 같은 구조)
sealed class LedgerException(
    val code: String,
    message: String,
) : RuntimeException(message)

// 같은 멱등 키로 내용이 다른 요청이 왔다: 클라이언트 버그이거나 키 재사용. 성공으로 응답하면 돈이 틀어진다
class IdempotencyConflictException(idempotencyKey: String) :
    LedgerException("IDEMPOTENCY_CONFLICT", "같은 멱등 키로 다른 내용의 요청이 왔습니다: $idempotencyKey")

// 취소하려는 원래 전표가 없다: 키 오타이거나, 원래 출금이 실제로는 처리되지 않았다 → 404.
// 없는 출금을 취소해 주면 미정산 계정에서 들어온 적 없는 돈이 고객에게 간다 (돈이 허공에서 생김)
class OriginalEntryNotFoundException(idempotencyKey: String) :
    LedgerException("ORIGINAL_ENTRY_NOT_FOUND", "취소할 원래 거래가 없습니다: $idempotencyKey")

// 이 API로 취소할 수 없는 거래다 (타행 출금만 취소 가능). 입금·당행 송금의 키를 넣은 경우 → 422
class NotCancellableEntryException(idempotencyKey: String, type: String) :
    LedgerException("NOT_CANCELLABLE_ENTRY", "취소할 수 없는 거래입니다: $idempotencyKey ($type)")
