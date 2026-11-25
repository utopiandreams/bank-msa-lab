package lab.bank.reconciliation.domain

/** 예외 항목 종류 (ADR-0006). 정상(MATCHED)은 항목으로 남기지 않는다 (결정 10) */
enum class ItemKind {
    /** 송금이 아직 끝 상태가 아님 → 판정하지 않고 이월 (결정 6). 하루 넘게 안 끝나면 경보 */
    PENDING,

    /** 송금 상태로 보면 있어야 할 전표가 core에 없다 (예: 완료인데 출금 전표 없음). 자정 경계일 수 있어 하루 이월 */
    MISSING_ENTRY,

    /** 있으면 안 되는 전표가 있다: 실패한 송금에 전표가 있거나, 송금 기록이 없는 전표 (돈은 움직였는데 송금은 모름) → 즉시 경보 */
    UNEXPECTED_ENTRY,

    /** 짝은 있는데 값이 다르다: 금액, 취소가 가리키는 원래 전표 → 즉시 경보 */
    MISMATCH,

    /** 그날 원장 차변 합 ≠ 대변 합 (시산표) → 즉시 경보 */
    TRIAL_BALANCE,
}
