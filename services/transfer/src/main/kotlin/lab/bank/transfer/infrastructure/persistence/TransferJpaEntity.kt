package lab.bank.transfer.infrastructure.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import lab.bank.transfer.domain.TransferStatus
import java.time.Instant
import java.util.UUID

/**
 * 송금 테이블 매핑 (저장 모델). 업무 규칙은 없다 → 규칙은 domain/Transfer에.
 * core와 달리 "엔티티 = 도메인"이 아닌 이유: transfer는 헥사고날 (CLAUDE.md 7장). 도메인을 JPA와 분리해
 * 포트를 가짜로 바꿔 끼우는 테스트(타임아웃·결과 불명)를 쉽게 한다. 대가가 이 클래스와 TransferMapper.
 *
 * 필드가 var인 이유: 저장 모델이라 상태 보호가 목적이 아니다. 매퍼만 쓴다.
 */
@Entity
@Table(name = "transfer", schema = "transfer")
class TransferJpaEntity(
    @Id
    var id: UUID,

    // UNIQUE (uq_transfer_idempotency_key, Flyway V2)
    @Column(nullable = false, updatable = false, length = 64)
    var idempotencyKey: String,

    @Column(nullable = false, updatable = false)
    var fromAccountId: UUID,

    @Column(nullable = false, updatable = false)
    var toAccountId: UUID,

    @Column(nullable = false, updatable = false)
    var amount: Long,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: TransferStatus,

    @Column(length = 50)
    var failureCode: String?,

    var coreEntryId: UUID?,

    @Column(nullable = false, updatable = false)
    var createdAt: Instant,

    @Column(nullable = false)
    var updatedAt: Instant,

    // null이면 Spring Data가 새 엔티티로 보고 INSERT (ID를 앱이 만들기 때문, core와 같은 이유)
    @Version
    var version: Long?,
)
