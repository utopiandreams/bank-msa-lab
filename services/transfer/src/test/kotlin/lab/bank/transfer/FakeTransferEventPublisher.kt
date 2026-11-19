package lab.bank.transfer

import lab.bank.transfer.application.port.out.TransferEventPublisher
import lab.bank.transfer.domain.InterbankTransfer
import lab.bank.transfer.domain.InterbankTransferStatus
import lab.bank.transfer.domain.Transfer
import lab.bank.transfer.domain.TransferStatus
import java.util.UUID

/** 가짜 발행기: 발행된 송금을 "그 순간의 상태"로 기록한다 (가변 객체라 나중에 바뀌어도 호출 시점 값이 남게) */
class FakeTransferEventPublisher : TransferEventPublisher {

    data class Published(val transferId: UUID, val status: TransferStatus, val failureCode: String?)

    val published = mutableListOf<Published>()

    // 타행: 상태 enum이 달라 따로 모은다. 도메인 값 그대로 (출금 실패 사유 / 외부 거절 사유)
    data class PublishedInterbank(val transferId: UUID, val status: InterbankTransferStatus, val failureCode: String?, val externalRejectReason: String?)

    val publishedInterbank = mutableListOf<PublishedInterbank>()

    override fun publishFinished(transfer: Transfer) {
        published += Published(transfer.id, transfer.status, transfer.failureCode)
    }

    override fun publishFinished(transfer: InterbankTransfer) {
        publishedInterbank += PublishedInterbank(transfer.id, transfer.status, transfer.failureCode, transfer.externalRejectReason)
    }

    fun reset() {
        published.clear()
        publishedInterbank.clear()
    }
}
