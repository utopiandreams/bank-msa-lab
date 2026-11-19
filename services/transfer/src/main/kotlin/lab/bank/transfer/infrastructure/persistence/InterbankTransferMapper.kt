package lab.bank.transfer.infrastructure.persistence

import lab.bank.transfer.domain.Amount
import lab.bank.transfer.domain.BankCode
import lab.bank.transfer.domain.IdempotencyKey
import lab.bank.transfer.domain.InterbankTransfer
import lab.bank.transfer.domain.UserId

// 타행 송금 도메인 ↔ 저장 모델 변환 (TransferMapper와 같은 역할). 값 객체 ↔ 원시 값
object InterbankTransferMapper {

    fun toEntity(transfer: InterbankTransfer) = InterbankTransferJpaEntity(
        id = transfer.id,
        requesterId = transfer.requesterId.value,
        idempotencyKey = transfer.idempotencyKey.value,
        fromAccountId = transfer.fromAccountId,
        amount = transfer.amount.value,
        status = transfer.status,
        failureCode = transfer.failureCode,
        coreEntryId = transfer.coreEntryId,
        createdAt = transfer.createdAt,
        updatedAt = transfer.updatedAt,
        version = transfer.version,
        toBankCode = transfer.toBankCode.value,
        toAccountNumber = transfer.toAccountId,
        externalRejectReason = transfer.externalRejectReason,
        cancelEntryId = transfer.cancelEntryId,
        detailCreatedAt = transfer.createdAt,
    )

    // 복원용 생성자: 상태 전이 검사를 거치지 않고 저장된 그대로 되살린다
    fun toDomain(entity: InterbankTransferJpaEntity) = InterbankTransfer(
        id = entity.id,
        requesterId = UserId(entity.requesterId),
        idempotencyKey = IdempotencyKey(entity.idempotencyKey),
        fromAccountId = entity.fromAccountId,
        toAccountId = entity.toAccountNumber,
        toBankCode = BankCode(entity.toBankCode),
        amount = Amount(entity.amount),
        status = entity.status,
        failureCode = entity.failureCode,
        coreEntryId = entity.coreEntryId,
        cancelEntryId = entity.cancelEntryId,
        externalRejectReason = entity.externalRejectReason,
        createdAt = entity.createdAt,
        updatedAt = entity.updatedAt,
        version = entity.version,
    )
}
