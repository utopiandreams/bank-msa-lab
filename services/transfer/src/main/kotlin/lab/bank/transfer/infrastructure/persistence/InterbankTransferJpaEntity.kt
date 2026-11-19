package lab.bank.transfer.infrastructure.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.PrimaryKeyJoinColumn
import jakarta.persistence.SecondaryTable
import jakarta.persistence.Table
import jakarta.persistence.Version
import lab.bank.transfer.domain.InterbankTransferStatus
import org.hibernate.annotations.SQLRestriction
import java.time.Instant
import java.util.UUID

/**
 * 타행 송금 저장 모델. Flyway V4 "공통 + 상세" 두 테이블을 엔티티 하나로 매핑한다 (@SecondaryTable):
 *   transfer.transfer           = 공통 (요청자, 고객 멱등 키, 상태, 금액 …), kind = 'INTERBANK'
 *   transfer.interbank_transfer = 상세 (받는 은행·계좌번호, 외부 거절 사유, 취소 전표), transfer_id로 1:1
 * 저장하면 Hibernate가 두 테이블에 같이 INSERT/UPDATE한다 (같은 트랜잭션). 업무 규칙은 없다 → domain/InterbankTransfer
 *
 * 당행 TransferJpaEntity와 같은 테이블을 다른 엔티티로 매핑하는 이유: status의 enum 타입이 다르다 (상속으로 묶으면 status 필드를 공유해야 함).
 * @SQLRestriction: 읽을 때 타행 행만. 없으면 당행 행(PROCESSING 등)을 InterbankTransferStatus로 읽다가 매핑 예외가 난다.
 */
@Entity
@Table(name = "transfer", schema = "transfer")
@SecondaryTable(
    name = "interbank_transfer",
    schema = "transfer",
    pkJoinColumns = [PrimaryKeyJoinColumn(name = "transfer_id")],
)
@SQLRestriction("kind = 'INTERBANK'")
class InterbankTransferJpaEntity(
    @Id
    var id: UUID,

    @Column(nullable = false, updatable = false, length = 64)
    var requesterId: String,

    // (requester_id, idempotency_key) UNIQUE (uq_transfer_requester_idempotency_key, Flyway V3). 당행·타행을 통틀어 유일
    @Column(nullable = false, updatable = false, length = 64)
    var idempotencyKey: String,

    @Column(nullable = false, updatable = false)
    var fromAccountId: UUID,

    @Column(nullable = false, updatable = false)
    var amount: Long,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    var status: InterbankTransferStatus,

    // 출금 실패 사유 (core 거절)
    @Column(length = 50)
    var failureCode: String?,

    // 타행 출금 전표
    var coreEntryId: UUID?,

    @Column(nullable = false, updatable = false)
    var createdAt: Instant,

    @Column(nullable = false)
    var updatedAt: Instant,

    @Version
    var version: Long?,

    // ---- 상세 테이블 (interbank_transfer) ----

    @Column(table = "interbank_transfer", nullable = false, updatable = false, length = 10)
    var toBankCode: String,

    @Column(table = "interbank_transfer", nullable = false, updatable = false, length = 30)
    var toAccountNumber: String,

    @Column(table = "interbank_transfer", length = 50)
    var externalRejectReason: String?,

    @Column(table = "interbank_transfer")
    var cancelEntryId: UUID?,

    // 상세 행이 만들어진 시각. 공통 행과 같은 순간에 같이 INSERT되므로 매퍼가 createdAt과 같은 값을 넣는다
    @Column(table = "interbank_transfer", name = "created_at", nullable = false, updatable = false)
    var detailCreatedAt: Instant,
) {
    // 송금 종류. 타행 엔티티는 항상 INTERBANK (DB 기본값은 DOMESTIC이라 반드시 넣어야 한다)
    @Column(nullable = false, updatable = false, length = 20)
    var kind: String = KIND

    companion object {
        const val KIND = "INTERBANK"
    }
}
