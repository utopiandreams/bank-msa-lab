package lab.bank.reconciliation.domain

/** 예외 항목 종류 (ADR-0006). 정상(MATCHED)은 항목으로 남기지 않는다 (결정 10) */
enum class ItemKind {
    /** 송금이 아직 끝 상태가 아님 → 판정하지 않고 이월 (결정 6). 하루 넘게 안 끝나면 경보 */
    PENDING,

    // ---- transfer ↔ core ----
    /** 송금 상태로 보면 있어야 할 전표가 core에 없다. 자정 경계일 수 있어 하루 이월 */
    MISSING_ENTRY,

    /** 있으면 안 되는 전표가 있다: 실패한 송금에 전표가 있거나, 송금 기록이 없는 전표 → 즉시 경보 */
    UNEXPECTED_ENTRY,

    /** 짝은 있는데 값이 다르다: 금액·받는 계좌, 취소가 가리키는 원래 전표 → 즉시 경보 */
    MISMATCH,

    /** 그날 원장 차변 합 ≠ 대변 합 (시산표) → 즉시 경보 */
    TRIAL_BALANCE,

    // ---- transfer ↔ 외부 (결정 7 판정표) ----
    /** 외부에 있어야 할 기록(입금·거절)이 없다. 외부 처리일이 하루 늦을 수 있어 하루 이월 */
    MISSING_EXTERNAL,

    /** 우리 송금 기록이 없는 외부 거래. 시스템 간 시계 차이일 수 있어 하루 이월 (결정 6) */
    EXTERNAL_ONLY,

    /** 우리는 출금을 돌려줬거나 보낸 적이 없는데 외부는 입금했다 = **은행 손실** → 즉시 경보 */
    DOUBLE_PAYMENT,

    /** 우리는 완료라고 했는데 외부는 거절했다 = 고객 돈은 빠졌는데 받는 사람은 못 받음 (**고객 손실**) → 즉시 경보 */
    CUSTOMER_LOSS,
    ;

    /** 이월 없이 바로 경보할 종류 (결정 7·14) */
    val immediate: Boolean
        get() = this in setOf(UNEXPECTED_ENTRY, MISMATCH, TRIAL_BALANCE, DOUBLE_PAYMENT, CUSTOMER_LOSS)
}
