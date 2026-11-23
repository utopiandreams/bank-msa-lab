package lab.bank.transfer.infrastructure.outbox

import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer
import lab.bank.transfer.domain.OutboxEvent
import lab.bank.transfer.infrastructure.kafka.KafkaOutboxSender
import lab.bank.transfer.infrastructure.tracing.TraceParents
import lab.bank.transfer.infrastructure.persistence.OutboxEventMapper
import lab.bank.transfer.infrastructure.persistence.OutboxJpaRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.time.Instant

/**
 * Outbox 릴레이 (폴링). 미발행 행을 Kafka로 옮기고 발행 표시한다. transfer 파드마다 하나씩 돈다.
 *
 * 한 회차 = **한 트랜잭션**: 잡기(FOR UPDATE SKIP LOCKED) → 한 건씩 보내고 ack 확인 → markPublished → 커밋.
 * - 잠금은 커밋 때 풀린다 → 보내는 동안 다른 파드의 릴레이는 이 행들을 건너뛰고 다른 행을 잡는다 (같은 행을 동시에 보내지 않음)
 * - 트랜잭션 안에서 원격 호출(Kafka)을 하는 건 pitfalls 8의 의도적 예외다. 잠금을 쥔 채 보내야 중복 전송을 막으니까.
 *   대가는 그동안 쥐는 DB 커넥션 하나 → batchSize와 sendTimeout으로 상한을 두고, 실패하면 그 회차를 바로 끝낸다
 *
 * 어디서 죽어도 유실은 없다 (pitfalls 30):
 * - 보내기 전에 죽음 → 롤백, 잠금 풀림 → 다음 회차가 다시 잡음
 * - 보낸 뒤 커밋 전에 죽음 → 롤백 → 다시 보냄 = **중복** (같은 eventId라 컨슈머가 거름)
 */
@Component
@ConditionalOnProperty(prefix = "outbox.relay", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class OutboxRelay(
    private val repository: OutboxJpaRepository,
    private val sender: KafkaOutboxSender,
    private val transactionTemplate: TransactionTemplate,
    private val properties: OutboxProperties,
) {

    // fixedDelay: 앞 회차가 끝난 뒤부터 잰다 → 한 파드 안에서 회차가 겹치지 않는다
    @Scheduled(fixedDelayString = "\${outbox.relay.poll-interval:1s}")
    fun relay() {
        val published = transactionTemplate.execute { relayOneBatch() } ?: 0
        if (published > 0) log.info("outbox 릴레이: {}건 발행", published)
    }

    private fun relayOneBatch(): Int {
        val batch = repository.findUnpublished(properties.relay.batchSize.toLong())
        var published = 0
        for (entity in batch) {
            val event = OutboxEventMapper.toDomain(entity)
            val span = publishSpan(event)
            try {
                // 이 구간을 현재로 올려 둔 채 보낸다 → 에이전트가 만드는 Kafka 발행 구간과 메시지 헤더가 이 구간(= 원래 요청)에 이어진다
                span.makeCurrent().use { sender.send(event, properties.relay.sendTimeout) }
            } catch (e: Exception) {
                span.recordException(e)
                span.setStatus(StatusCode.ERROR)
                // 한 건이 실패하면 이번 회차는 여기서 끝낸다: 대개 Kafka 장애라 나머지도 건마다 타임아웃만큼 기다리며 실패한다.
                // 앞에서 보낸 것들은 표시된 채 커밋된다. 이 행과 나머지는 다음 회차에 다시 (오래된 순이라 순서도 유지)
                // 스택 트레이스는 남기지 않는다: Kafka가 죽어 있으면 poll 간격마다 찍힌다
                log.warn("outbox 발행 실패, 이번 회차 중단 (다음 회차에 재시도): id={}, aggregateId={}, cause={}", event.id, event.aggregateId, e.toString())
                break
            } finally {
                span.end()
            }
            event.markPublished(Instant.now())
            repository.save(OutboxEventMapper.toEntity(event))   // version이 있어 UPDATE (같은 트랜잭션의 관리 엔티티에 병합)
            published++
        }
        return published
    }

    /**
     * 행 하나의 발행 구간 (9주 차). 부모 = 이 행을 만든 요청(행에 적힌 traceparent), 링크 = 이번 릴레이 회차.
     *
     * 왜 회차 전체가 아니라 행마다인가: 구간의 부모는 하나뿐이다. 한 회차는 여러 송금의 행을 묶어 가져오므로
     * 회차 구간(`OutboxRelay.relay`)은 어느 한 요청의 자식이 될 수 없다 → 회차는 자기 trace로 두고, 행마다 자식을 만든다.
     * 회차와의 관계는 링크(부모는 아니지만 "같이 처리됨")로만 남긴다.
     *
     * 결과: 요청 trace = gateway → transfer → core → outbox INSERT ··(대기)·· outbox publish → Kafka → notification
     * 대기 시간(INSERT → 발행)은 구간 사이 빈칸으로도 보이고, 속성 outbox.wait_ms로도 남긴다 (13주 차 체류 시간 지표의 단서)
     */
    private fun publishSpan(event: OutboxEvent): Span {
        val parent = TraceParents.toContext(event.traceParent)
        return tracer.spanBuilder("outbox publish")
            .setParent(parent)                       // 행에 문맥이 없으면 부모 없는 문맥 → 새 trace
            .addLink(Span.current().spanContext)     // 지금 도는 릴레이 회차 (에이전트가 @Scheduled에 만든 구간). 유효하지 않으면 무시됨
            .setAttribute("outbox.event_id", event.id.toString())
            .setAttribute("outbox.aggregate_id", event.aggregateId.toString())
            .setAttribute("outbox.wait_ms", Duration.between(event.createdAt, Instant.now()).toMillis())
            .startSpan()
    }

    companion object {
        // 계측 이름 = 이 구간을 누가 만들었나 (Tempo에서 otel.scope.name으로 보인다). 에이전트가 없으면 아무것도 안 하는 tracer
        private val tracer: Tracer = GlobalOpenTelemetry.getTracer("lab.bank.transfer.outbox")
        private val log = LoggerFactory.getLogger(OutboxRelay::class.java)
    }
}
