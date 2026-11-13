package lab.bank.transfer.domain

/**
 * 고객 멱등 키: 클라이언트가 송금 의도 하나마다 만들어 Idempotency-Key 헤더로 보낸다.
 * 재시도할 때 같은 키를 다시 보내면 송금이 한 번만 일어난다. 키 하나 = 의도 하나.
 *
 * 송금 ID(transfer.id)와 다르다: 송금 ID는 transfer가 만들어 core에 보내는 키 (transfer → core 구간),
 * 이건 고객이 만들어 transfer에 보내는 키 (고객 → transfer 구간). 구간마다 자기 멱등 키.
 *
 * 길이 제한은 DB 컬럼(varchar(64))과 맞춘다. 안 막으면 INSERT가 DataIntegrityViolationException으로 터져
 * 중복 키로 오인될 수 있다 (core에서 겪은 버그, 2026-11-13).
 */
@JvmInline
value class IdempotencyKey(val value: String) {
    init {
        if (value.isBlank()) throw InvalidIdempotencyKeyException("비어 있음")
        if (value.length > MAX_LENGTH) throw InvalidIdempotencyKeyException("${MAX_LENGTH}자 초과 (${value.length}자)")
    }

    companion object {
        const val MAX_LENGTH = 64

        // 헤더 값 → 키. 헤더가 없으면 400 (돈이 움직이는 API라 멱등 키를 필수로 한다)
        fun fromHeader(header: String?): IdempotencyKey =
            IdempotencyKey(header ?: throw InvalidIdempotencyKeyException("Idempotency-Key 헤더가 없음"))
    }
}
