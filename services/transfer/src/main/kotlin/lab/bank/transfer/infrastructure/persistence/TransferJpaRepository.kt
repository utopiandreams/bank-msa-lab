package lab.bank.transfer.infrastructure.persistence

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

// Spring Data 리포지토리. 어댑터(TransferPersistenceAdapter)만 쓴다. 유스케이스는 포트(TransferRepository)를 쓴다
interface TransferJpaRepository : JpaRepository<TransferJpaEntity, UUID> {

    fun findByIdempotencyKey(idempotencyKey: String): TransferJpaEntity?
}
