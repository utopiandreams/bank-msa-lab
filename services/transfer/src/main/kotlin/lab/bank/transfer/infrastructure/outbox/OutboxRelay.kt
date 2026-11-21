package lab.bank.transfer.infrastructure.outbox

import lab.bank.transfer.infrastructure.kafka.KafkaOutboxSender
import lab.bank.transfer.infrastructure.persistence.OutboxEventMapper
import lab.bank.transfer.infrastructure.persistence.OutboxJpaRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
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
            try {
                sender.send(event, properties.relay.sendTimeout)
            } catch (e: Exception) {
                // 한 건이 실패하면 이번 회차는 여기서 끝낸다: 대개 Kafka 장애라 나머지도 건마다 타임아웃만큼 기다리며 실패한다.
                // 앞에서 보낸 것들은 표시된 채 커밋된다. 이 행과 나머지는 다음 회차에 다시 (오래된 순이라 순서도 유지)
                // 스택 트레이스는 남기지 않는다: Kafka가 죽어 있으면 poll 간격마다 찍힌다
                log.warn("outbox 발행 실패, 이번 회차 중단 (다음 회차에 재시도): id={}, aggregateId={}, cause={}", event.id, event.aggregateId, e.toString())
                break
            }
            event.markPublished(Instant.now())
            repository.save(OutboxEventMapper.toEntity(event))   // version이 있어 UPDATE (같은 트랜잭션의 관리 엔티티에 병합)
            published++
        }
        return published
    }

    companion object {
        private val log = LoggerFactory.getLogger(OutboxRelay::class.java)
    }
}
