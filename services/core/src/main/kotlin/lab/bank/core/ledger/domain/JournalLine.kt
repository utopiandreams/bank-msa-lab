package lab.bank.core.ledger.domain

import jakarta.persistence.*
import java.util.UUID

/**
 * 분개 줄. "돈이 어느 계정에서 어느 방향으로 얼마나" 움직였는지 한 줄.
 *
 * 생성자가 internal: 줄은 JournalEntry의 팩토리를 통해서만 만든다 (애그리거트 루트 경유).
 * entry_id 컬럼은 JournalEntry 쪽 @JoinColumn이 채운다.
 */
@Entity
@Table(name = "journal_line", schema = "ledger")
class JournalLine internal constructor(
    ledgerAccount: LedgerAccount,
    accountId: UUID?,
    direction: Direction,
    amount: Long,
) {
    @Id
    val id: UUID = UUID.randomUUID()

    // 계정 (현금 / 고객예금). 계좌(Account)와 다른 개념이라 이름을 구분했다
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 30)
    val ledgerAccount: LedgerAccount = ledgerAccount

    // 고객 계좌 ID. 고객예금 계정일 때만 있다. account 모듈의 테이블을 참조하지 않고 값으로만 들고 있다 (FK 없음)
    @Column(updatable = false)
    val accountId: UUID? = accountId

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 10)
    val direction: Direction = direction

    // 원 단위, 항상 양수. 늘었는지 줄었는지는 부호가 아니라 direction이 말한다
    @Column(nullable = false, updatable = false)
    val amount: Long = amount

    init {
        require(amount > 0) { "분개 금액은 양수다: $amount" }
        require((ledgerAccount == LedgerAccount.CUSTOMER_DEPOSIT) == (accountId != null)) {
            "고객예금 계정에만 계좌 ID가 있다: $ledgerAccount, accountId=$accountId"
        }
    }
}
