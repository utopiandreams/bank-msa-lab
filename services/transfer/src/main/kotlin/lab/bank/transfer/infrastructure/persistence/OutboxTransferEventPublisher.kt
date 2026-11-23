package lab.bank.transfer.infrastructure.persistence

import lab.bank.common.event.TransferFinishedEvent
import lab.bank.transfer.application.port.out.TransferEventPublisher
import lab.bank.transfer.domain.InterbankTransfer
import lab.bank.transfer.domain.InterbankTransferStatus
import lab.bank.transfer.domain.OutboxEvent
import lab.bank.transfer.domain.Transfer
import lab.bank.transfer.infrastructure.tracing.TraceParents
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.UUID

/**
 * 발행 포트의 Outbox 구현 (6주 차). Kafka로 바로 보내지 않고, 호출한 트랜잭션 안에서 outbox_event에 INSERT만 한다.
 * 실제 전송은 릴레이가 커밋 뒤에 한다. 포트 계약(예외를 삼키지 않는다)은 TransferEventPublisher 주석.
 *
 * 이 클래스 자체가 "발행 포트의 영속성 어댑터"라 JPA 리포지토리를 직접 쓴다 (어댑터가 다른 아웃바운드 포트를 거치지 않는다).
 */
@Component
class OutboxTransferEventPublisher(
    private val outboxJpaRepository: OutboxJpaRepository,
    private val jsonMapper: JsonMapper,
    ): TransferEventPublisher {

    override fun publishFinished(transfer: Transfer) {
        val eventId = UUID.randomUUID()
        val payload = jsonMapper.writeValueAsString(
            TransferFinishedEvent(
                eventId = eventId,
                schemaVersion = TransferFinishedEvent.SCHEMA_VERSION,
                occurredAt = Instant.now(),
                transferId = transfer.id,
                requesterId = transfer.requesterId.value,
                fromAccountId = transfer.fromAccountId,
                toAccountId = transfer.toAccountId,
                amount = transfer.amount.value,
                status = transfer.status.name,
                failureCode = transfer.failureCode,
            )
        )
        outboxJpaRepository.save(
            OutboxEventMapper.toEntity(
                OutboxEvent.create(
                    eventId = eventId,
                    aggregateId = transfer.id,
                    topic = TransferFinishedEvent.TOPIC,
                    messageKey = transfer.id.toString(),
                    payload = payload,
                    now = Instant.now(),
                    traceParent = TraceParents.current(),   // 지금 요청의 trace 문맥을 행에 적는다 (릴레이가 이어 붙임)
                )
            )
        )
    }

    override fun publishFinished(transfer: InterbankTransfer) {
        val eventId = UUID.randomUUID()
        val payload = jsonMapper.writeValueAsString(
            TransferFinishedEvent(
                eventId = eventId,
                schemaVersion = TransferFinishedEvent.SCHEMA_VERSION,
                occurredAt = Instant.now(),
                transferId = transfer.id,
                requesterId = transfer.requesterId.value,
                fromAccountId = transfer.fromAccountId,
                toAccountId = null,
                amount = transfer.amount.value,
                status = transfer.status.name,
                failureCode = failureCodeOf(transfer),
                toBankCode = transfer.toBankCode.value,
                toAccountNumber = transfer.toAccountId,
            )
        )
        outboxJpaRepository.save(
            OutboxEventMapper.toEntity(
                OutboxEvent.create(
                    eventId = eventId,
                    aggregateId = transfer.id,
                    topic = TransferFinishedEvent.TOPIC,
                    messageKey = transfer.id.toString(),
                    payload = payload,
                    now = Instant.now(),
                    traceParent = TraceParents.current(),   // 지금 요청의 trace 문맥을 행에 적는다 (릴레이가 이어 붙임)
                )
            )
        )
    }

    // 이벤트의 failureCode = 고객에게 알릴 "왜". 도메인은 출처별로 칸을 나눴지만(출금 실패 / 외부 거절) 이벤트 계약은 칸 하나다
    //   FAILED    → core 출금 거절 사유 (INSUFFICIENT_BALANCE ...)
    //   CANCELLED → 외부 거절 사유 (ACCOUNT_NOT_FOUND ...): notification이 "왜 돌려줬나" 문구를 만든다
    private fun failureCodeOf(transfer: InterbankTransfer): String? = when (transfer.status) {
        InterbankTransferStatus.CANCELLED -> transfer.externalRejectReason
        else -> transfer.failureCode
    }
}