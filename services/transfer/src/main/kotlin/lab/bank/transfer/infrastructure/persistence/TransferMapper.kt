package lab.bank.transfer.infrastructure.persistence

import lab.bank.transfer.domain.Transfer

// 도메인 ↔ 저장 모델 변환. 헥사고날의 대가: 필드를 추가하면 Transfer, TransferJpaEntity, 여기, Flyway 네 군데를 고친다
object TransferMapper {

    fun toEntity(transfer: Transfer) = TransferJpaEntity(
        id = transfer.id,
        fromAccountId = transfer.fromAccountId,
        toAccountId = transfer.toAccountId,
        amount = transfer.amount,
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
        fromAccountId = entity.fromAccountId,
        toAccountId = entity.toAccountId,
        amount = entity.amount,
        status = entity.status,
        failureCode = entity.failureCode,
        coreEntryId = entity.coreEntryId,
        createdAt = entity.createdAt,
        updatedAt = entity.updatedAt,
        version = entity.version,
    )
}
