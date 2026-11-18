package lab.bank.transfer.application.port.out

import lab.bank.transfer.domain.Amount
import java.util.UUID

/**
 * 외부 결제망(타행)에 보낼 이체 내용.
 * @property transferId 송금 ID = 외부 멱등 키. 같은 송금을 다시 보내도 외부는 한 번만 처리한다 (8주 차 "같은 키 재전송으로 확인"의 전제)
 */
data class ExternalTransferCommand(
    val transferId: UUID,
    val toBankCode: String,
    val toAccountNumber: String,
    val amount: Amount,
)
