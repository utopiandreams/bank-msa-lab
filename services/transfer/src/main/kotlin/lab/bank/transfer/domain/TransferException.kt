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

// 받는 은행 코드가 형식(숫자 3자리)에 안 맞거나, 타행 송금인데 우리 은행 코드다 → 400
// 우리 은행 코드: 당행 송금은 받는 계좌를 계좌 ID(UUID)로 받으므로 타행 요청(계좌번호 문자열)을 당행으로 돌려 처리할 수 없다
class InvalidBankCodeException(bankCode: String, reason: String) :
    TransferException("INVALID_BANK_CODE", "받는 은행 코드가 올바르지 않습니다 ($reason): $bankCode")

class InvalidIdempotencyKeyException(reason: String) :
    TransferException("INVALID_IDEMPOTENCY_KEY", "멱등 키가 올바르지 않습니다: $reason")

// 누가 요청했는지 모른다 (X-User-Id 없음). 클라이언트가 고칠 수 있는 400이 아니라 인증 문제 → 401
class MissingUserException(reason: String) :
    TransferException("MISSING_USER", "요청자를 알 수 없습니다: $reason")

// 같은 키로 다른 내용(계좌·금액)이 왔다: 키를 재사용한 클라이언트 버그. 먼저 처리된 송금과 충돌 → 409
// reason: 무엇과 충돌했는지 (같은 종류의 다른 내용 / 다른 종류의 송금). 클라이언트가 할 일은 같다 → 코드는 하나, 문구로 원인을 알린다
class IdempotencyConflictException(idempotencyKey: IdempotencyKey, reason: String = "같은 멱등 키로 다른 내용의 송금이 이미 있습니다") :
    TransferException("IDEMPOTENCY_CONFLICT", "$reason: ${idempotencyKey.value}")

// 송금 요청 본문의 조합이 틀렸다 (당행인데 받는 계좌 ID가 없음, 타행인데 계좌번호가 없음 등) → 400
class InvalidTransferRequestException(reason: String) :
    TransferException("INVALID_TRANSFER_REQUEST", "송금 요청이 올바르지 않습니다: $reason")
