package lab.bank.core.ledger.domain

// 계정. 계좌(Account)와 다른 개념. 지금은 몇 개뿐이고 런타임에 늘지 않아서 enum, 커지면 계정과목표 테이블로 뺀다
enum class LedgerAccount {
    CASH,               // 현금 (자산)
    CUSTOMER_DEPOSIT,   // 고객 예금 (부채)
    // 타행이체 미정산 (가계정). 타행으로 나가는 돈이 정산(하루치 배치, 8주 차) 전까지 머문다.
    // 잔액을 따로 저장하지 않고 이 계정 줄의 합으로 본다. 정산·취소가 끝나면 합이 0이어야 한다 (대사 포인트, ADR-0004)
    INTERBANK_UNSETTLED,
}
