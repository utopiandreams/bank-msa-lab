package lab.bank.transfer.infrastructure.persistence

import lab.bank.transfer.domain.OutboxEvent

// 도메인 ↔ 저장 모델 변환 (TransferMapper와 같은 구조)
object OutboxEventMapper {

    fun toEntity(event: OutboxEvent) = OutboxEventJpaEntity(
        id = event.id,
        aggregateId = event.aggregateId,
        topic = event.topic,
        messageKey = event.messageKey,
        payload = event.payload,
        createdAt = event.createdAt,
        publishedAt = event.publishedAt,
        version = event.version,
        traceParent = event.traceParent,
    )

    // 복원용 생성자: 저장된 그대로 되살린다 (markPublished 검사를 거치지 않음)
    fun toDomain(entity: OutboxEventJpaEntity) = OutboxEvent(
        id = entity.id,
        aggregateId = entity.aggregateId,
        topic = entity.topic,
        messageKey = entity.messageKey,
        payload = entity.payload,
        createdAt = entity.createdAt,
        publishedAt = entity.publishedAt,
        version = entity.version,
        traceParent = entity.traceParent,
    )
}
