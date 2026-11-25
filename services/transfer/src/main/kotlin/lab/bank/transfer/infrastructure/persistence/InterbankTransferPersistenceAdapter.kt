package lab.bank.transfer.infrastructure.persistence

import lab.bank.transfer.application.port.out.DuplicateIdempotencyKeyException
import lab.bank.transfer.application.port.out.InterbankTransferRepository
import lab.bank.transfer.domain.IdempotencyKey
import lab.bank.transfer.domain.InterbankTransfer
import lab.bank.transfer.domain.UserId
import org.hibernate.exception.ConstraintViolationException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

// 타행 송금 저장 포트의 JPA 구현. 저장 방식(새 객체 merge + saveAndFlush, 멱등 키 위반 번역)은 TransferPersistenceAdapter와 같다
@Component
class InterbankTransferPersistenceAdapter(
    private val jpaRepository: InterbankTransferJpaRepository,
) : InterbankTransferRepository {

    override fun save(transfer: InterbankTransfer): InterbankTransfer =
        try {
            InterbankTransferMapper.toDomain(jpaRepository.saveAndFlush(InterbankTransferMapper.toEntity(transfer)))
        } catch (e: DataIntegrityViolationException) {
            if (violatedConstraint(e).equals(IDEMPOTENCY_KEY_CONSTRAINT, ignoreCase = true)) {
                throw DuplicateIdempotencyKeyException(transfer.idempotencyKey, e)
            }
            throw e
        }

    override fun findById(id: UUID): InterbankTransfer? =
        jpaRepository.findByIdOrNull(id)?.let(InterbankTransferMapper::toDomain)

    override fun findByIdempotencyKey(requesterId: UserId, idempotencyKey: IdempotencyKey): InterbankTransfer? =
        jpaRepository.findByRequesterIdAndIdempotencyKey(requesterId.value, idempotencyKey.value)
            ?.let(InterbankTransferMapper::toDomain)

    override fun findStuckForUpdate(coreSentBefore: Instant, externalSentBefore: Instant, limit: Int): List<InterbankTransfer> {
        val ids = jpaRepository.lockStuckIds(coreSentBefore, externalSentBefore, limit)
        if (ids.isEmpty()) return emptyList()
        // findAllById는 순서를 보장하지 않는다 → 오래된 순으로 다시 정렬
        return jpaRepository.findAllById(ids).map(InterbankTransferMapper::toDomain).sortedBy { it.updatedAt }
    }

    private fun violatedConstraint(e: DataIntegrityViolationException): String? =
        generateSequence<Throwable>(e) { it.cause }
            .filterIsInstance<ConstraintViolationException>()
            .firstOrNull()
            ?.constraintName

    companion object {
        private const val IDEMPOTENCY_KEY_CONSTRAINT = "uq_transfer_requester_idempotency_key"
    }
}
