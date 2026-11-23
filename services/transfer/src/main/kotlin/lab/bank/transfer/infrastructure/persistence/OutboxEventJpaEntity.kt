package lab.bank.transfer.infrastructure.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.time.Instant
import java.util.UUID

/**
 * outbox_event 테이블 매핑 (저장 모델, Flyway V6). 규칙은 domain/OutboxEvent에. 필드가 var인 이유는 TransferJpaEntity와 같다.
 */
@Entity
@Table(name = "outbox_event", schema = "transfer")
class OutboxEventJpaEntity(
    @Id
    var id: UUID,

    @Column(nullable = false, updatable = false)
    var aggregateId: UUID,

    @Column(nullable = false, updatable = false, length = 100)
    var topic: String,

    @Column(nullable = false, updatable = false, length = 100)
    var messageKey: String,

    @Column(nullable = false, updatable = false, columnDefinition = "text")
    var payload: String,

    @Column(nullable = false, updatable = false)
    var createdAt: Instant,

    var publishedAt: Instant?,

    // null이면 Spring Data가 새 엔티티로 보고 INSERT (ID를 앱이 만들기 때문)
    @Version
    var version: Long?,

    // 만든 요청의 W3C traceparent (V7). 릴레이가 이걸 부모로 발행한다
    @Column(updatable = false, length = 64)
    var traceParent: String? = null,
)
