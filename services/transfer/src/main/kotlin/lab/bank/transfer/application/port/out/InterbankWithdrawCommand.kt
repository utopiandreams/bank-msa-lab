package lab.bank.transfer.application.port.out

import lab.bank.transfer.domain.Amount
import lab.bank.transfer.domain.InterbankTransfer
import lab.bank.transfer.domain.UserId
import java.util.UUID

/**
 * core 타행 출금 요청 내용. 원시 값 여러 개 대신 묶음으로 받는다 (UUID 두 개의 순서를 바꿔 넘기는 실수 방지, CorePostingPort와 같은 이유)
 *
 * @property transferId 송금 ID = core 멱등 키 (나중에 취소할 때 이 키로 원래 출금을 가리킨다)
 * @property requesterId core가 "출금 계좌의 주인 == 요청자"를 검사한다 (BOLA)
 */
data class InterbankWithdrawCommand(
    val transferId: UUID,
    val requesterId: UserId,
    val fromAccountId: UUID,
    val amount: Amount,
) {
    companion object {
        fun fromDomain(interbankTransfer: InterbankTransfer) =
            InterbankWithdrawCommand(
                interbankTransfer.id,
                interbankTransfer.requesterId,
                interbankTransfer.fromAccountId,
                interbankTransfer.amount,
            )
    }
}
