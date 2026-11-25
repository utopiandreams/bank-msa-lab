package lab.bank.transfer.infrastructure.persistence

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.time.Instant
import java.util.UUID

interface OutboxJpaRepository : JpaRepository<OutboxEventJpaEntity, UUID> {

    // 네이티브 쿼리는 @Table(schema)를 안 쓴다 → 스키마를 명시 (search_path에 기대면 계정 이름이 바뀔 때 깨진다, 2026-11-14 Flyway 장애)
    // 잠금은 트랜잭션이 끝날 때 풀린다 → 반드시 트랜잭션 안에서 부른다 (밖이면 문장 끝에 바로 풀려 두 릴레이가 같은 행을 잡는다)
    @Query(
        nativeQuery = true,
        value = "SELECT o.* FROM transfer.outbox_event o WHERE o.published_at IS NULL ORDER BY o.created_at LIMIT :limit FOR UPDATE SKIP LOCKED"
    )
    fun findUnpublished(limit: Long): List<OutboxEventJpaEntity>

    // 정리 배치: 발행한 지 오래된 행만. published_at이 NULL(미발행)이면 비교 결과가 NULL이라 지워지지 않는다
    // 한 번에 많이 지우면 한 트랜잭션이 커진다. 지금 규모(하루 수천 건)는 괜찮고, 커지면 LIMIT으로 나눠 지운다
    @Modifying
    @Query("DELETE FROM OutboxEventJpaEntity o WHERE o.publishedAt < :before")
    fun deletePublishedBefore(before: Instant): Int

    // 적체 지표 (OutboxBacklogMetrics): 미발행 행 수와 가장 오래된 미발행 행의 나이(초). 부분 색인 ix_outbox_event_unpublished만 훑는다.
    // 잠금 없이 읽는다 (지표는 대략이면 된다, 릴레이를 막지 않게). 나이는 DB 시계로 잰다 (created_at도 같은 기준이라 가정, 앱·DB 시계 차이는 무시)
    @Query(
        nativeQuery = true,
        value = "SELECT count(*) AS unpublished, COALESCE(EXTRACT(EPOCH FROM now() - min(o.created_at)), 0)::float8 AS oldestAgeSeconds " +
            "FROM transfer.outbox_event o WHERE o.published_at IS NULL"
    )
    fun backlog(): OutboxBacklogRow
}
