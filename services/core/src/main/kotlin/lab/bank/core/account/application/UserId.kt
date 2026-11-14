package lab.bank.core.account.application

import lab.bank.core.account.domain.exception.MissingUserException

/**
 * 요청한 사용자 = gateway가 검증한 토큰의 sub (ADR-0002). 두 길로 들어온다:
 *   고객 → gateway → core /accounts/...            gateway가 X-User-Id를 덮어씀
 *   고객 → gateway → transfer → core /postings/transfers   transfer가 받은 X-User-Id를 그대로 전파
 * core는 어느 길이든 X-User-Id 하나만 읽는다. 다시 인증하지 않는다 (gateway를 믿음, 내부 위조는 백로그 "제로 트러스트")
 *
 * 길이는 accounts.owner_id 컬럼과 gateway 발급 API(64자)에 맞춘다.
 */
@JvmInline
value class UserId(val value: String) {
    init {
        if (value.isBlank()) throw MissingUserException("비어 있음")
        if (value.length > MAX_LENGTH) throw MissingUserException("${MAX_LENGTH}자 초과")
    }

    companion object {
        const val MAX_LENGTH = 64
        const val HEADER = "X-User-Id"

        // 헤더가 없다 = gateway·transfer를 거치지 않은 호출이거나 설정 실수 → 401
        fun fromHeader(header: String?): UserId = UserId(header ?: throw MissingUserException("$HEADER 헤더가 없음"))
    }
}
