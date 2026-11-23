package lab.bank.transfer.domain

import java.time.Instant
import java.util.UUID

/**
 * 보낼 이벤트 한 건 (Transactional Outbox, 6주 차).
 *
 * 송금 상태 변경과 **같은 트랜잭션**으로 저장된다 → "송금은 끝났는데 이벤트는 없음"(커밋과 발행 사이 유실)이 사라진다.
 * 업무 규칙이라기보다 전달 보장 장치지만, 상태(미발행 → 발행)와 그 전이 규칙이 있어 송금처럼 모델로 둔다.
 *
 * id = eventId. 생성할 때 한 번 정한다. 릴레이가 재시도해도 같은 eventId로 나가야 컨슈머가 중복을 거른다 (pitfalls 29).
 * payload는 이미 직렬화된 JSON: 릴레이는 내용을 모르고 topic·key·payload를 그대로 옮기기만 한다.
 * traceParent는 이 이벤트를 만든 요청의 trace 문맥(W3C 문자열, 9주 차). 업무와 무관한 운반용 칸이라 도메인은 해석하지 않는다.
 */
class OutboxEvent(
    val id: UUID,
    val aggregateId: UUID,     // 이벤트를 만든 송금 ID
    val topic: String,
    val messageKey: String,
    val payload: String,
    val createdAt: Instant,
    publishedAt: Instant?,
    val version: Long?,        // 새 행은 null → INSERT (Transfer와 같은 이유)
    val traceParent: String? = null,   // 없으면 릴레이가 새 trace로 발행
) {
    // null = 아직 안 보냄
    var publishedAt: Instant? = publishedAt
        private set

    val isPublished: Boolean get() = publishedAt != null

    /**
     * Kafka가 받았다고 확인(acks)한 뒤에 부른다.
     * 이미 발행된 행을 또 표시하면 버그다: SKIP LOCKED로 한 행은 한 릴레이만 잡으므로 일어나면 안 된다.
     */
    fun markPublished(now: Instant) {
        check(publishedAt == null) { "이미 발행된 이벤트: id=$id, publishedAt=$publishedAt" }
        publishedAt = now
    }

    companion object {
        fun create(
            eventId: UUID, aggregateId: UUID, topic: String, messageKey: String, payload: String, now: Instant,
            traceParent: String? = null,
        ) =
            OutboxEvent(
                id = eventId,
                aggregateId = aggregateId,
                topic = topic,
                messageKey = messageKey,
                payload = payload,
                createdAt = now,
                publishedAt = null,
                version = null,
                traceParent = traceParent,
            )
    }
}
