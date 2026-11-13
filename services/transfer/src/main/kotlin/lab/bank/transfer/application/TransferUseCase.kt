package lab.bank.transfer.application

import lab.bank.transfer.domain.Amount
import lab.bank.transfer.domain.Transfer
import java.util.UUID

interface TransferUseCase {

    // 최종 상태(COMPLETED / FAILED / UNKNOWN)가 반영된 송금을 돌려준다. 컨트롤러가 응답(송금 ID, 상태)을 만든다
    fun transfer(fromAccountId: UUID, toAccountId: UUID, amount: Amount): Transfer

}