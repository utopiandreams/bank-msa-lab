package lab.bank.transfer.application

import lab.bank.transfer.domain.Amount
import lab.bank.transfer.domain.BankCode
import lab.bank.transfer.domain.IdempotencyKey
import lab.bank.transfer.domain.UserId
import java.util.UUID

interface InterbankTransferUseCase {

    /**
     * 타행 송금 요청 (ADR-0004 오케스트레이션 사가: 출금 → 외부 송금 → 거절이면 출금 취소).
     * 같은 요청자가 같은 고객 멱등 키로 다시 오면 새로 만들지 않고 기존 송금을 돌려준다 (당행과 같은 규칙, 키는 당행·타행 통틀어 유일).
     * 고객 응답은 동기 (2026-11-19 결정): 사가가 끝나거나 결과 불명으로 멈춘 시점의 상태를 돌려준다.
     */
    fun transfer(
        requesterId: UserId,
        idempotencyKey: IdempotencyKey,
        fromAccountId: UUID,
        toBankCode: BankCode,
        toAccountNumber: String,
        amount: Amount,
    ): InterbankTransferRequestResult
}
