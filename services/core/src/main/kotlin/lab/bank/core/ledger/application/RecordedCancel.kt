package lab.bank.core.ledger.application

import lab.bank.core.shared.Amount
import java.util.UUID

// 취소 기록 결과. posting이 잔액을 되돌리려면 어느 계좌에 얼마를 넣을지 알아야 해서 원래 전표의 값을 함께 준다
data class RecordedCancel(
    val entryId: UUID,
    val replayed: Boolean,         // true: 이미 취소된 전표 (이번 호출에서는 아무것도 기록하지 않음)
    val idempotencyKey: String,    // core가 만든 취소 전표의 키 "{원래 키}-cancel" (상태 조회 URL용)
    val accountId: UUID,
    val amount: Amount,
)
