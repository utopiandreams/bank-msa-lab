package lab.bank.transfer.infrastructure.kafka

import lab.bank.transfer.domain.OutboxEvent
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * outbox 이벤트 한 건을 Kafka로 보내고 **브로커가 받았다고 확인(acks=all)할 때까지 기다린다.** 릴레이 전용 부품.
 *
 * 이전(KafkaTransferEventPublisher, 4~5주 차)과 반대인 점:
 * - 이전: 송금 API 스레드에서 보내서, 기다리지 않고(콜백에서 로그만) 실패도 삼켰다 → 송금 응답을 붙잡지 않으려고
 * - 지금: 릴레이 스레드에서 보내고, **결과를 호출자에게 돌려준다** (실패하면 예외).
 *   릴레이는 이 결과를 보고 markPublished 여부를 정한다. 실패를 삼키면 안 보냈는데 "보냄"으로 표시돼 다시 유실된다
 *
 * 이벤트 내용(JSON)은 모른다. outbox에 적힌 topic·key·payload를 그대로 옮긴다 (JSON은 OutboxTransferEventPublisher가 만든다).
 *
 * 타임아웃 두 겹:
 * - 브로커에 아예 못 붙으면 send() 자체가 max.block.ms(1초)만큼 막혔다 예외 (application.yaml, pitfalls 20)
 * - 보낸 뒤 확인은 프로듀서의 delivery.timeout.ms(5초) 안에 결론이 난다. timeout은 그걸 못 믿을 때의 안전망
 * 타임아웃 = 미발행이 아니다 (pitfalls 29). 릴레이가 다시 보내 중복이 생길 수 있고, 컨슈머가 eventId로 거른다
 */
@Component
class KafkaOutboxSender(
    private val kafkaTemplate: KafkaTemplate<String, String>,
) {

    fun send(event: OutboxEvent, timeout: Duration) {
        kafkaTemplate.send(event.topic, event.messageKey, event.payload)
            .get(timeout.toMillis(), TimeUnit.MILLISECONDS)
    }
}
