package lab.bank.bankmock.domain

/** 외부 결제망의 답. 5주 차는 이 두 가지뿐 (타임아웃·응답 없음은 8주 차, 15주 차) */
enum class InterbankOutcome {
    /** 받는 은행이 입금했다. 우리 쪽에서 되돌릴 수 없다 (ADR-0004 피벗) */
    ACCEPTED,

    /** 받는 은행이 거절했다. 돈은 움직이지 않았다 → 보내는 쪽이 출금을 보상한다 */
    REJECTED,
}
