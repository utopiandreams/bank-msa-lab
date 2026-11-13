package lab.bank.transfer.application

import lab.bank.transfer.domain.Transfer

// 송금 요청 결과. replayed = 같은 멱등 키의 기존 송금을 돌려줬다 (새로 만들지 않음). core의 PostingResult.replayed와 같은 의미
data class TransferRequestResult(
    val transfer: Transfer,
    val replayed: Boolean,
)
