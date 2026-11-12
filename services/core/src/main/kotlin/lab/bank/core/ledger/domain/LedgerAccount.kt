package lab.bank.core.ledger.domain

// 계정. 계좌(Account)와 다른 개념. 지금은 몇 개뿐이고 런타임에 늘지 않아서 enum, 커지면 계정과목표 테이블로 뺀다
enum class LedgerAccount {
    CASH,               // 현금 (자산)
    CUSTOMER_DEPOSIT,   // 고객 예금 (부채)
    // 5주 차: INTERBANK_UNSETTLED (타행이체 미정산)
}
