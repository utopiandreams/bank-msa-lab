package lab.bank.core.posting.application.exception

import java.util.UUID

/**
 * posting 모듈의 입력 검증 예외: 요청만 보고 판단할 수 있고, 클라이언트가 요청을 고치면 해결된다 (→ 400).
 * 트랜잭션을 열기 전에 던진다. 같은 규칙이 도메인의 require에도 있지만, 그쪽은 "여기까지 왔다면 우리 버그"인 마지막 방어선(→ 500)
 *
 * account의 InvalidAmountException을 재사용하지 않은 이유: from == to처럼 계좌 하나가 아니라 "거래 요청"의 규칙이 섞여 있어서
 */
sealed class PostingException(
    val code: String,
    message: String,
) : RuntimeException(message)

class InvalidPostingAmountException(amount: Long) :
    PostingException("INVALID_AMOUNT", "금액은 0보다 커야 합니다: $amount")

class InvalidIdempotencyKeyException(reason: String) :
    PostingException("INVALID_IDEMPOTENCY_KEY", "멱등 키가 올바르지 않습니다: $reason")

class SameAccountTransferException(accountId: UUID) :
    PostingException("SAME_ACCOUNT_TRANSFER", "같은 계좌로는 송금할 수 없습니다: $accountId")
