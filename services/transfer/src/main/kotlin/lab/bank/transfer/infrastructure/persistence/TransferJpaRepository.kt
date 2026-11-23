package lab.bank.transfer.infrastructure.persistence

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.time.Instant
import java.util.UUID

// Spring Data 리포지토리. 어댑터(TransferPersistenceAdapter)만 쓴다. 유스케이스는 포트(TransferRepository)를 쓴다
interface TransferJpaRepository : JpaRepository<TransferJpaEntity, UUID> {

    fun findByRequesterIdAndIdempotencyKey(requesterId: String, idempotencyKey: String): TransferJpaEntity?

    // 네이티브 쿼리에는 엔티티의 @SQLRestriction(kind = 'DOMESTIC')이 붙지 않는다 → 직접 적는다 (빼면 타행 행을 TransferStatus로 읽다 터짐)
    // 상태 필터는 부분 인덱스 ix_transfer_unresolved(미해결 행만 담음)가 받친다. 해소 대상은 평소 거의 0건이라 정렬 비용도 작다
    @Query(
        value = """
            SELECT t.* FROM transfer.transfer t
            WHERE t.kind = 'DOMESTIC' AND t.status IN ('PROCESSING', 'UNKNOWN') AND t.created_at < :sentBefore
            ORDER BY t.created_at
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
        """,
        nativeQuery = true,
    )
    fun findUnresolvedForUpdate(sentBefore: Instant, limit: Int): List<TransferJpaEntity>
}
