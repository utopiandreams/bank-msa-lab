package lab.bank.core.posting.api

enum class PostingStatus {
    // 이 키로 처리된 거래가 커밋돼 있다
    COMPLETED,

    // 이 키로 커밋된 거래가 없다. "실패"가 아니다: 조회 순간 처리 중일 수도 있다
    // → 호출자는 실패로 확정하지 말고 같은 키로 다시 보낸다 (멱등이라 안전)
    NOT_PROCESSED,
}
