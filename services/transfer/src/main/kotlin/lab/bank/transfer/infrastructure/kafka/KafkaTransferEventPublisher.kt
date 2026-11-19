package lab.bank.transfer.infrastructure.kafka

import lab.bank.common.event.TransferFinishedEvent
import lab.bank.transfer.application.port.out.TransferEventPublisher
import lab.bank.transfer.domain.InterbankTransfer
import lab.bank.transfer.domain.InterbankTransferStatus
import lab.bank.transfer.domain.Transfer
import org.slf4j.LoggerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.UUID

/**
 * 송금 이벤트를 Kafka로 보낸다 (아웃바운드 어댑터). 토픽 transfer-events, 키 = 송금 ID (ADR-0003).
 *
 * 보내기는 비동기다: send()는 Future를 돌려주고, 브로커의 응답(acks=all)은 콜백에서 확인한다 → 송금 API를 기다리게 하지 않는다.
 * 단, 브로커에 아예 못 붙으면 send() 자체가 메타데이터를 기다리며 막힌다 → max.block.ms를 짧게 (application.yaml).
 * 기본값 60초면 Kafka가 죽었을 때 송금 API가 건마다 60초씩 멈춘다 (pitfalls 20).
 */
@Component
class KafkaTransferEventPublisher(
    private val kafkaTemplate: KafkaTemplate<String, String>,
    private val jsonMapper: JsonMapper,
) : TransferEventPublisher {

    override fun publishFinished(transfer: Transfer) = send(
        TransferFinishedEvent(
            eventId = UUID.randomUUID(),
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

    // 타행: 받는 쪽은 은행 코드 + 계좌번호 (toAccountId 없음). 상태 이름(COMPLETED·FAILED·CANCELLED)은 이벤트 계약과 같다
    override fun publishFinished(transfer: InterbankTransfer) = send(
        TransferFinishedEvent(
            eventId = UUID.randomUUID(),
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

    // 이벤트의 failureCode = 고객에게 알릴 "왜". 도메인은 출처별로 칸을 나눴지만(출금 실패 / 외부 거절) 이벤트 계약은 칸 하나다
    //   FAILED    → core 출금 거절 사유 (INSUFFICIENT_BALANCE ...)
    //   CANCELLED → 외부 거절 사유 (ACCOUNT_NOT_FOUND ...): notification이 "왜 돌려줬나" 문구를 만든다
    private fun failureCodeOf(transfer: InterbankTransfer): String? = when (transfer.status) {
        InterbankTransferStatus.CANCELLED -> transfer.externalRejectReason
        else -> transfer.failureCode
    }

    private fun send(event: TransferFinishedEvent) {
        try {
            kafkaTemplate.send(TransferFinishedEvent.TOPIC, event.transferId.toString(), jsonMapper.writeValueAsString(event))
                .whenComplete { result, ex ->
                    if (ex != null) {
                        // 이벤트 유실 (6주 차 Outbox로 막을 구멍). 송금 ID·이벤트 ID를 남겨 나중에 대사·재발행의 단서로
                        log.error("송금 이벤트 발행 실패(유실): transferId={}, eventId={}, status={}", event.transferId, event.eventId, event.status, ex)
                    } else {
                        log.info("송금 이벤트 발행: transferId={}, eventId={}, partition={}, offset={}",
                            event.transferId, event.eventId, result.recordMetadata.partition(), result.recordMetadata.offset())
                    }
                }
        } catch (e: Exception) {
            // send() 자체가 실패 (브로커에 못 붙어 max.block.ms 초과 등). 포트 계약: 예외를 던지지 않는다
            log.error("송금 이벤트 발행 실패(유실): transferId={}, eventId={}, status={}", event.transferId, event.eventId, event.status, e)
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(KafkaTransferEventPublisher::class.java)
    }
}
