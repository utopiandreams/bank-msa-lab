package lab.bank.transfer.domain

/**
 * 요청한 사용자. gateway가 검증한 토큰의 sub를 X-User-Id 헤더로 넘겨 준다 (ADR-0002 결정 3).
 * transfer는 이 헤더를 믿는다: gateway가 들어온 X-User-Id를 지우고 덮어쓰기 때문.
 * (클러스터 내부에서의 위조는 막지 못한다 → 백로그 "NetworkPolicy · 제로 트러스트")
 *
 * 길이 제한은 DB 컬럼(varchar(64))과 gateway 발급 API의 제한과 맞춘다.
 */
@JvmInline
value class UserId(val value: String) {
    init {
        if (value.isBlank()) throw MissingUserException("비어 있음")
        if (value.length > MAX_LENGTH) throw MissingUserException("${MAX_LENGTH}자 초과")
    }

    companion object {
        const val MAX_LENGTH = 64

        // 헤더가 없다 = gateway를 거치지 않은 호출이거나 설정 실수 → 401 (누구인지 모름)
        fun fromHeader(header: String?): UserId =
            UserId(header ?: throw MissingUserException("X-User-Id 헤더가 없음"))
    }
}
