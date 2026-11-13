package lab.bank.transfer.infrastructure.persistence

import lab.bank.transfer.domain.Amount
import lab.bank.transfer.domain.IdempotencyKey
import lab.bank.transfer.domain.Transfer

// 도메인 ↔ 저장 모델 변환. 헥사고날의 대가: 필드를 추가하면 Transfer, TransferJpaEntity, 여기, Flyway 네 군데를 고친다
object TransferMapper {

    fun toEntity(transfer: Transfer) = TransferJpaEntity(
        id = transfer.id,
        idempotencyKey = transfer.idempotencyKey.value,
        fromAccountId = transfer.fromAccountId,
        toAccountId = transfer.toAccountId,
        amount = transfer.amount.value,   // 값 객체 → 원시 값 (JPA 엔티티는 Long)
        status = transfer.status,
        failureCode = transfer.failureCode,
        coreEntryId = transfer.coreEntryId,
        createdAt = transfer.createdAt,
        updatedAt = transfer.updatedAt,
        version = transfer.version,
    )

    // 복원용 생성자를 쓴다: 도메인 메서드(상태 전이 검사)를 거치지 않고 저장된 그대로 되살린다
    fun toDomain(entity: TransferJpaEntity) = Transfer(
        id = entity.id,
        idempotencyKey = IdempotencyKey(entity.idempotencyKey),
        fromAccountId = entity.fromAccountId,
        toAccountId = entity.toAccountId,
        amount = Amount(entity.amount),   // 원시 값 → 값 객체. DB CHECK(amount > 0)가 있어 여기서 터지면 데이터가 깨진 것
        status = entity.status,
        failureCode = entity.failureCode,
        coreEntryId = entity.coreEntryId,
        createdAt = entity.createdAt,
        updatedAt = entity.updatedAt,
        version = entity.version,
    )
}
