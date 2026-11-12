package lab.bank.core.ledger.domain

import jakarta.persistence.*
import java.util.UUID

/**
 * 분개 줄. "돈이 어느 계정에서 어느 방향으로 얼마나" 움직였는지 한 줄.
 *
 * 생성자가 internal: 줄은 JournalEntry의 팩토리를 통해서만 만든다 (애그리거트 루트 경유).
 * 양방향 매핑에서 FK(entry_id)의 주인은 이쪽(@ManyToOne). 소속 거래는 JournalEntry가 생성될 때 붙여 준다.
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

    // 소속 거래. 줄만 따로 조회해도(대사: 계좌별 줄 조회) 어느 거래에서 왔는지 알 수 있다
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "entry_id", nullable = false, updatable = false)
    lateinit var entry: JournalEntry
        protected set

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

    // JournalEntry 생성 시에만 호출된다. 이미 다른 거래에 붙은 줄을 옮기는 일은 없다 (거래 불변)
    internal fun attachTo(entry: JournalEntry) {
        check(!this::entry.isInitialized) { "이미 거래에 속한 분개 줄이다: $id" }
        this.entry = entry
    }
}
