package lab.bank.transfer.application

import lab.bank.transfer.domain.Amount
import lab.bank.transfer.domain.IdempotencyKey
import java.util.UUID

interface TransferUseCase {

    /**
     * 송금 요청. 같은 고객 멱등 키로 다시 오면 새로 만들지 않고 기존 송금을 돌려준다 (T-2, CLAUDE.md 5장).
     * replayed: 이번 요청이 기존 송금을 돌려준 것인지 (컨트롤러가 201 / 200을 고른다)
     */
    fun transfer(idempotencyKey: IdempotencyKey, fromAccountId: UUID, toAccountId: UUID, amount: Amount): TransferRequestResult

}