package lab.bank.transfer.infrastructure.persistence

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.time.Instant
import java.util.UUID

// Spring Data 리포지토리. 어댑터(InterbankTransferPersistenceAdapter)만 쓴다. 엔티티의 @SQLRestriction으로 타행 행만 조회된다
interface InterbankTransferJpaRepository : JpaRepository<InterbankTransferJpaEntity, UUID> {

    fun findByRequesterIdAndIdempotencyKey(requesterId: String, idempotencyKey: String): InterbankTransferJpaEntity?

    /**
     * 해소 대상 ID만 잠그며 고른다. 엔티티를 바로 읽지 않는 이유: 상세 테이블과 공통 테이블에 둘 다 created_at이 있어
     * 네이티브 쿼리로 두 테이블을 이어 읽으면 컬럼 이름이 겹친다. 잠금은 공통 행(t)에만: 상태·버전이 거기 있다.
     * 이 트랜잭션이 잠갔으니, 이어서 findAllById로 읽는 사이에 아무도 이 행들을 못 바꾼다.
     * kind 조건: 네이티브 쿼리에는 @SQLRestriction이 안 붙는다. 상태 조건은 부분 인덱스 ix_transfer_unresolved가 받친다
     */
    @Query(
        value = """
            SELECT t.id FROM transfer.transfer t
            WHERE t.kind = 'INTERBANK'
              AND (
                (t.status IN ('WITHDRAW_REQUESTED', 'WITHDRAWN', 'WITHDRAW_CANCEL_REQUESTED') AND t.updated_at < :coreSentBefore)
                OR (t.status = 'EXTERNAL_BANK_REQUESTED' AND t.updated_at < :externalSentBefore)
              )
            ORDER BY t.updated_at
            LIMIT :limit
            FOR UPDATE OF t SKIP LOCKED
        """,
        nativeQuery = true,
    )
    fun lockStuckIds(coreSentBefore: Instant, externalSentBefore: Instant, limit: Int): List<UUID>
}
