package lab.bank.reconciliation.infrastructure.kafka

import lab.bank.common.event.ExtractCompletedEvent
import lab.bank.reconciliation.application.ExtractArrival
import lab.bank.reconciliation.application.ReconRepository
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import tools.jackson.core.JacksonException
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.json.JsonMapper
import java.time.Instant

/**
 * 인박스 (설계 연습 09 결정 15): 추출 완료 알림을 DB에 **기록만** 하고 바로 끝낸다 → 오프셋 커밋.
 * 대사(수십 초~분)를 여기서 돌리면 max.poll.interval.ms를 넘겨 리밸런싱 → 같은 알림이 다시 배달돼 같은 날짜가 동시에 돈다.
 * 대사는 ReconWorker가 DB를 보고 돈다. 같은 알림이 두 번 와도 extract_id PK로 한 번만 들어간다 (멱등 컨슈머)
 */
@Component
class ExtractCompletedListener(
    jsonMapper: JsonMapper,
    private val repository: ReconRepository,
) {
    private val reader = jsonMapper.readerFor(ExtractCompletedEvent::class.java)
        .without(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)

    @KafkaListener(topics = [ExtractCompletedEvent.TOPIC])
    fun onMessage(record: ConsumerRecord<String, String>) {
        val event: ExtractCompletedEvent = try {
            reader.readValue(record.value())
        } catch (e: JacksonException) {
            log.error("읽을 수 없는 추출 알림, 건너뜀: offset={}, key={}", record.offset(), record.key(), e)
            return
        }
        val inserted = repository.insertArrival(
            ExtractArrival(event.extractId, event.source, event.businessDate, event.objectKey, event.rowCount, event.amountSum,
                event.sha256, event.debitSum, event.creditSum, event.extractedAt, Instant.now()),
        )
        log.info("추출 도착 source={} date={} rows={} 새로받음={}", event.source, event.businessDate, event.rowCount, inserted)
    }

    companion object {
        private val log = LoggerFactory.getLogger(ExtractCompletedListener::class.java)
    }
}
