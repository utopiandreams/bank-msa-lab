package lab.bank.transfer.application

import lab.bank.transfer.domain.InterbankTransfer

// 타행 송금 요청 결과. replayed = 같은 멱등 키의 기존 송금을 돌려줬다 (TransferRequestResult와 같은 의미)
data class InterbankTransferRequestResult(
    val transfer: InterbankTransfer,
    val replayed: Boolean,
)
