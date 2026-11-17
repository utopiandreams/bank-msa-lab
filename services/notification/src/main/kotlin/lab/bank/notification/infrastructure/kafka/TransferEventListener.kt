package lab.bank.notification.infrastructure.kafka

import lab.bank.common.event.TransferFinishedEvent
import lab.bank.notification.application.NotificationService
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import tools.jackson.core.JacksonException
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.json.JsonMapper

/**
 * transfer-events 구독 (컨슈머 그룹 notification). 처리가 끝나면(메서드가 정상 반환) Spring이 오프셋을 커밋한다.
 * 처리 후 커밋 전에 죽으면 같은 메시지를 다시 받는다 (at-least-once) → NotificationService가 event_id로 걸러 낸다.
 */
@Component
class TransferEventListener(
    jsonMapper: JsonMapper,
    private val notificationService: NotificationService,
) {
    // 모르는 필드는 무시한다: 생산자(transfer)가 필드를 추가해도 소비자가 안 깨지게 (ADR-0003 결정 4, 공유 클래스의 완화책)
    private val reader = jsonMapper.readerFor(TransferFinishedEvent::class.java)
        .without(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)

    @KafkaListener(topics = [TransferFinishedEvent.TOPIC])
    fun onMessage(record: ConsumerRecord<String, String>) {
        val event: TransferFinishedEvent = try {
            reader.readValue(record.value())
        } catch (e: JacksonException) {
            // 깨진 메시지(poison pill): 몇 번을 다시 시도해도 실패한다. 예외를 던지면 재시도하느라 이 파티션 전체가 막힌다
            // → 로그를 남기고 건너뛴다. 나중에 Dead Letter Topic으로 보내 사람이 보게 한다
            log.error("읽을 수 없는 이벤트, 건너뜀: partition={}, offset={}, key={}", record.partition(), record.offset(), record.key(), e)
            return
        }
        val outcome = notificationService.handle(event)   // DB 장애 같은 일시 오류는 예외 → KafkaConsumerConfig의 재시도
        log.info("이벤트 처리: transferId={}, eventId={}, partition={}, offset={}, 결과={}",
            event.transferId, event.eventId, record.partition(), record.offset(), outcome)
    }

    companion object {
        private val log = LoggerFactory.getLogger(TransferEventListener::class.java)
    }
}
