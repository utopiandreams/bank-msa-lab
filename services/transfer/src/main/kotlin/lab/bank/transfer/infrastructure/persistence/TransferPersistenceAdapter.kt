package lab.bank.transfer.infrastructure.persistence

import lab.bank.transfer.application.port.out.DuplicateIdempotencyKeyException
import lab.bank.transfer.application.port.out.TransferRepository
import lab.bank.transfer.domain.IdempotencyKey
import lab.bank.transfer.domain.Transfer
import org.hibernate.exception.ConstraintViolationException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import java.util.UUID

// 저장 포트의 JPA 구현 (아웃바운드 어댑터)
@Component
class TransferPersistenceAdapter(
    private val jpaRepository: TransferJpaRepository,
) : TransferRepository {

    // 매번 새 엔티티 객체를 만들어 저장한다 (version이 있으면 merge → 버전 비교 → 다르면 낙관적 락 예외).
    // saveAndFlush: UPDATE를 지금 보내야 올라간 version을 돌려줄 수 있다 (flush 전엔 version이 그대로)
    // 새 송금의 INSERT도 saveAndFlush라 여기서 바로 실행된다 → UNIQUE 위반을 이 메서드 안에서 잡을 수 있다
    // (flush를 커밋 때로 미루면 예외가 트랜잭션 커밋 시점에 터져서 어댑터가 번역할 기회가 없다)
    override fun save(transfer: Transfer): Transfer =
        try {
            TransferMapper.toDomain(jpaRepository.saveAndFlush(TransferMapper.toEntity(transfer)))
        } catch (e: DataIntegrityViolationException) {
            if (violatedConstraint(e).equals(IDEMPOTENCY_KEY_CONSTRAINT, ignoreCase = true)) {
                throw DuplicateIdempotencyKeyException(transfer.idempotencyKey, e)
            }
            throw e   // 다른 제약 위반(금액 CHECK 등)은 우리 버그 → 그대로 500
        }

    override fun findById(id: UUID): Transfer? =
        jpaRepository.findByIdOrNull(id)?.let(TransferMapper::toDomain)

    override fun findByIdempotencyKey(idempotencyKey: IdempotencyKey): Transfer? =
        jpaRepository.findByIdempotencyKey(idempotencyKey.value)?.let(TransferMapper::toDomain)

    // Spring 예외 안쪽의 Hibernate 예외가 DB가 알려 준 제약 이름을 갖고 있다
    private fun violatedConstraint(e: DataIntegrityViolationException): String? =
        generateSequence<Throwable>(e) { it.cause }
            .filterIsInstance<ConstraintViolationException>()
            .firstOrNull()
            ?.constraintName

    companion object {
        private const val IDEMPOTENCY_KEY_CONSTRAINT = "uq_transfer_idempotency_key"   // Flyway V2
    }
}
