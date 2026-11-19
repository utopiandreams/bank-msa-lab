package lab.bank.transfer.infrastructure.persistence

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

// Spring Data 리포지토리. 어댑터(InterbankTransferPersistenceAdapter)만 쓴다. 엔티티의 @SQLRestriction으로 타행 행만 조회된다
interface InterbankTransferJpaRepository : JpaRepository<InterbankTransferJpaEntity, UUID> {

    fun findByRequesterIdAndIdempotencyKey(requesterId: String, idempotencyKey: String): InterbankTransferJpaEntity?
}
