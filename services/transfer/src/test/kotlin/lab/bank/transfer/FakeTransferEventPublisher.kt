package lab.bank.transfer

import lab.bank.transfer.application.port.out.TransferEventPublisher
import lab.bank.transfer.domain.Transfer
import lab.bank.transfer.domain.TransferStatus
import java.util.UUID

/** 가짜 발행기: 발행된 송금을 "그 순간의 상태"로 기록한다 (가변 객체라 나중에 바뀌어도 호출 시점 값이 남게) */
class FakeTransferEventPublisher : TransferEventPublisher {

    data class Published(val transferId: UUID, val status: TransferStatus, val failureCode: String?)

    val published = mutableListOf<Published>()

    override fun publishFinished(transfer: Transfer) {
        published += Published(transfer.id, transfer.status, transfer.failureCode)
    }

    fun reset() = published.clear()
}
