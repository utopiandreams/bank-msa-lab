package lab.bank.transfer.infrastructure.kafka

import lab.bank.common.event.ExtractCompletedEvent
import lab.bank.transfer.application.port.out.ExtractCompletedPublisher
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import java.util.concurrent.TimeUnit

/**
 * 추출 완료 알림을 Kafka로 바로 보낸다 (outbox를 거치지 않는다).
 * 송금 이벤트와 달리 DB 커밋과 함께 묶을 상태 변경이 없다: 추출은 읽기만 하고, 알림이 실패하면 추출 배치 전체가 실패로 끝나 다시 돌리면 된다.
 * 브로커 확인(acks=all)까지 기다린다. 실패를 삼키면 대사 서비스가 영영 모른다 (그건 대사 쪽 기한 경보가 잡는다, 결정 13)
 */
@Component
class KafkaExtractCompletedPublisher(
    private val kafkaTemplate: KafkaTemplate<String, String>,
    private val jsonMapper: JsonMapper,
) : ExtractCompletedPublisher {

    override fun publish(event: ExtractCompletedEvent) {
        kafkaTemplate.send(ExtractCompletedEvent.TOPIC, "${event.source}:${event.businessDate}", jsonMapper.writeValueAsString(event))
            .get(10, TimeUnit.SECONDS)
    }
}
