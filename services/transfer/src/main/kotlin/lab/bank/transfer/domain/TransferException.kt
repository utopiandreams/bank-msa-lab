package lab.bank.transfer.domain

import java.util.UUID

/**
 * transfer의 업무 예외. 클라이언트가 요청을 고치면 해결된다 (→ 400, HTTP 매핑은 api 계층의 예외 핸들러).
 * 도메인의 require/check(IllegalArgumentException/IllegalStateException)와 구분한다: 그건 우리 버그 → 500.
 *
 * sealed: 하위 타입을 추가하면 예외 핸들러의 when이 컴파일 에러로 매핑 누락을 알려 준다.
 * code는 응답 body(ProblemDetail)의 code 속성으로 나간다 (core와 같은 형식).
 */
sealed class TransferException(
    val code: String,
    message: String,
) : RuntimeException(message)

class InvalidAmountException(amount: Long) :
    TransferException("INVALID_AMOUNT", "송금 금액은 0보다 커야 합니다: $amount")

class SameAccountsException(fromAccountId: UUID, toAccountId: UUID) :
        TransferException("SAME_ACCOUNT_TRANSFER", "입금 계좌 번호와 출금 계좌번호가 같을 수 없습니다: $fromAccountId")

class InvalidIdempotencyKeyException(reason: String) :
    TransferException("INVALID_IDEMPOTENCY_KEY", "멱등 키가 올바르지 않습니다: $reason")

// 같은 키로 다른 내용(계좌·금액)이 왔다: 키를 재사용한 클라이언트 버그. 먼저 처리된 송금과 충돌 → 409
class IdempotencyConflictException(idempotencyKey: IdempotencyKey) :
    TransferException("IDEMPOTENCY_CONFLICT", "같은 멱등 키로 다른 내용의 송금이 이미 있습니다: ${idempotencyKey.value}")
