package lab.bank.core.shared

/**
 * 공유 값 객체를 만들 때의 검증 예외. 요청만 보고 판단할 수 있고 클라이언트가 고칠 수 있다 (→ 400).
 * API가 생기면 컨트롤러가 DTO의 원시 값을 값 객체로 바꾸는 순간 여기서 터진다.
 */
sealed class SharedException(
    val code: String,
    message: String,
) : RuntimeException(message)

class InvalidAmountException(amount: Long) :
    SharedException("INVALID_AMOUNT", "금액은 0보다 커야 합니다: $amount")

class InvalidIdempotencyKeyException(reason: String) :
    SharedException("INVALID_IDEMPOTENCY_KEY", "멱등 키가 올바르지 않습니다: $reason")
