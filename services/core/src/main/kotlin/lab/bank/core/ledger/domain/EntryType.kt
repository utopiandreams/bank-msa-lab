package lab.bank.core.ledger.domain

// 거래 종류
enum class EntryType {
    DEPOSIT,
    WITHDRAWAL,
    TRANSFER,
    INTERBANK_WITHDRAWAL,          // 타행 이체 출금: 고객 예금 → 미정산 (ADR-0004)
    INTERBANK_WITHDRAWAL_CANCEL,   // 타행 이체 출금 취소(보상): 원래 출금의 반대 전표. 원장은 불변이라 고치지 않고 새로 쓴다
}
