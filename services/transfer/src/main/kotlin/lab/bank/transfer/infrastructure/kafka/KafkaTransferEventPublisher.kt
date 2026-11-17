package lab.bank.transfer.infrastructure.kafka

import lab.bank.common.event.TransferFinishedEvent
import lab.bank.transfer.application.port.out.TransferEventPublisher
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

    override fun publishFinished(transfer: Transfer) {
        val event = TransferFinishedEvent(
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
        try {
            kafkaTemplate.send(TransferFinishedEvent.TOPIC, transfer.id.toString(), jsonMapper.writeValueAsString(event))
                .whenComplete { result, ex ->
                    if (ex != null) {
                        // 이벤트 유실 (6주 차 Outbox로 막을 구멍). 송금 ID·이벤트 ID를 남겨 나중에 대사·재발행의 단서로
                        log.error("송금 이벤트 발행 실패(유실): transferId={}, eventId={}, status={}", transfer.id, event.eventId, event.status, ex)
                    } else {
                        log.info("송금 이벤트 발행: transferId={}, eventId={}, partition={}, offset={}",
                            transfer.id, event.eventId, result.recordMetadata.partition(), result.recordMetadata.offset())
                    }
                }
        } catch (e: Exception) {
            // send() 자체가 실패 (브로커에 못 붙어 max.block.ms 초과 등). 포트 계약: 예외를 던지지 않는다
            log.error("송금 이벤트 발행 실패(유실): transferId={}, eventId={}, status={}", transfer.id, event.eventId, event.status, e)
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(KafkaTransferEventPublisher::class.java)
    }
}
