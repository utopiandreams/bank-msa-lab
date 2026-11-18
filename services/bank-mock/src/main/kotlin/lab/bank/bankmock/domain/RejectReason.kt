package lab.bank.bankmock.domain

enum class RejectReason {
    /** 받는 계좌가 없다. 실제 타행 거절 사유 중 가장 흔하다 (수취 계좌 번호 규칙으로 재현) */
    ACCOUNT_NOT_FOUND,

    /** 받는 은행이 그냥 거절했다 (거절률 실험 스위치로 재현) */
    RECEIVING_BANK_REJECTED,
}
