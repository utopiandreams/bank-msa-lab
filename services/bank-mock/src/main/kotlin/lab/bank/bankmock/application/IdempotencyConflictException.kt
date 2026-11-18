package lab.bank.bankmock.application

/** 같은 멱등 키로 내용이 다른 요청이 왔다. 클라이언트 버그이거나 키 재사용 → 409 */
class IdempotencyConflictException(val idempotencyKey: String) :
    RuntimeException("같은 멱등 키에 다른 내용: $idempotencyKey")
