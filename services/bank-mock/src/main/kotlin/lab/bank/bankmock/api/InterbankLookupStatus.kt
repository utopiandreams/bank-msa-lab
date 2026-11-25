package lab.bank.bankmock.api

/**
 * 조회 결과. 송금 응답(InterbankOutcome)에 NOT_FOUND가 더해진 것.
 * "처리 중"이 없는 이유: 보내는 쪽이 처리 상한이 지난 뒤에만 조회하기로 약속했다 (설계 연습 08 2부 결정 1)
 */
enum class InterbankLookupStatus {
    ACCEPTED,
    REJECTED,

    /** 기록 없음 = 처리하지 않았다 (받지 못했거나, 처리 상한을 넘겨 포기했다) */
    NOT_FOUND,
}
