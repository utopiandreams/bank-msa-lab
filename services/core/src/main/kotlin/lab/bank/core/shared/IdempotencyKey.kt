package lab.bank.core.shared

/**
 * 멱등 키: 요청한 쪽이 만든 요청 ID (입출금: 채널의 요청 ID, 송금: transfer의 송금 ID).
 *
 * 길이 제한은 DB 컬럼(ledger.journal_entry.idempotency_key varchar(64))과 맞춘다.
 * 막지 않으면 INSERT에서 DataIntegrityViolationException이 나서 중복 키 재시도에 잘못 잡히고 500으로 나간다.
 */
@JvmInline
value class IdempotencyKey(val value: String) {
    init {
        if (value.isBlank()) throw InvalidIdempotencyKeyException("비어 있음")
        if (value.length > MAX_LENGTH) throw InvalidIdempotencyKeyException("${MAX_LENGTH}자 초과 (${value.length}자)")
    }

    companion object {
        const val MAX_LENGTH = 64
    }
}
