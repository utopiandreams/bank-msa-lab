package lab.bank.core.posting.application.exception

import java.util.UUID

/**
 * posting 모듈의 입력 검증 예외: 요청만 보고 판단할 수 있고, 클라이언트가 요청을 고치면 해결된다 (→ 400).
 *
 * 금액·멱등 키처럼 값 하나로 판단되는 규칙은 shared의 값 객체(Amount, IdempotencyKey)가 만들어질 때 검사한다.
 * 여기에는 필드 여러 개를 함께 봐야 하는 "거래 요청"의 규칙만 둔다.
 */
sealed class PostingException(
    val code: String,
    message: String,
) : RuntimeException(message)

class SameAccountTransferException(accountId: UUID) :
    PostingException("SAME_ACCOUNT_TRANSFER", "같은 계좌로는 송금할 수 없습니다: $accountId")
