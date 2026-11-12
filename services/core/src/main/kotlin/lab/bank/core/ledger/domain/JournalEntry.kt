package lab.bank.core.ledger.domain

import jakarta.persistence.*
import java.time.Instant
import lab.bank.core.shared.Amount
import lab.bank.core.shared.IdempotencyKey
import java.util.UUID

/**
 * 거래 (분개 묶음). 입금 1건, 송금 1건이 각각 JournalEntry 1개.
 *
 * 애그리거트 루트: 분개 줄은 항상 이 거래를 통해서만 만들어지고 저장된다 ("차변 합 = 대변 합" 보호).
 * 거래는 불변이다. 잘못된 거래는 고치지 않고 반대 거래를 새로 남긴다.
 */
@Entity
@Table(name = "journal_entry", schema = "ledger")
class JournalEntry private constructor(
    idempotencyKey: String,
    type: EntryType,
    lines: List<JournalLine>,
) {
    @Id
    val id: UUID = UUID.randomUUID()

    // 요청한 쪽이 만든 멱등 키 (입출금: 채널의 요청 ID, 송금: transfer의 송금 ID). DB에 UNIQUE
    @Column(nullable = false, updatable = false, length = 64)
    val idempotencyKey: String = idempotencyKey

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 20)
    val type: EntryType = type

    @Column(nullable = false, updatable = false)
    val createdAt: Instant = Instant.now()

    // 양방향 1:N. FK의 주인은 JournalLine.entry (mappedBy). 줄은 거래와 함께 저장된다 (cascade)
    @OneToMany(mappedBy = "entry", cascade = [CascadeType.PERSIST], fetch = FetchType.LAZY)
    private val _lines: MutableList<JournalLine> = lines.toMutableList()

    val lines: List<JournalLine> get() = _lines.toList()

    // Account와 같은 이유: ID를 앱이 만들어서, version이 null이어야 Spring Data가 INSERT한다
    @Version
    var version: Long? = null
        protected set

    init {
        // require: 여기 걸리면 고객 잘못이 아니라 우리 코드 버그
        require(idempotencyKey.isNotBlank()) { "멱등 키가 비어 있다" }
        require(lines.size >= 2) { "분개 줄은 최소 2줄이다: ${lines.size}줄" }
        val debit = lines.filter { it.direction == Direction.DEBIT }.sumOf { it.amount }
        val credit = lines.filter { it.direction == Direction.CREDIT }.sumOf { it.amount }
        require(debit == credit) { "차변 합($debit)과 대변 합($credit)이 다르다" }

        // 양방향 연관관계의 양쪽을 함께 맞춘다. 한쪽만 넣으면 entry_id가 비어서 INSERT가 실패한다
        lines.forEach { it.attachTo(this) }
    }

    // 멱등 판정: 같은 키로 온 요청이 처음 요청과 같은 내용인가. 요청 필드(종류·계좌·금액)가 분개 줄에 전부 남아 있어서
    // 해시를 따로 저장하지 않고 "거래 종류 + 분개 줄 구성"을 비교한다. 줄 순서는 보장되지 않아서 개수 세기(멀티셋)로 비교
    fun isSameRequestAs(other: JournalEntry): Boolean =
        type == other.type && lineSignature() == other.lineSignature()

    private fun lineSignature(): Map<List<Any?>, Int> =
        _lines.groupingBy { listOf(it.ledgerAccount, it.accountId, it.direction, it.amount) }.eachCount()

    // 분개 규칙은 ledger가 갖는다. 고객 예금은 늘면 대변, 줄면 차변. 현금은 그 반대
    companion object {
        fun deposit(idempotencyKey: IdempotencyKey, accountId: UUID, amount: Amount) = JournalEntry(
            idempotencyKey.value, EntryType.DEPOSIT,
            listOf(
                JournalLine(LedgerAccount.CASH, null, Direction.DEBIT, amount.value),
                JournalLine(LedgerAccount.CUSTOMER_DEPOSIT, accountId, Direction.CREDIT, amount.value),
            ),
        )

        fun withdrawal(idempotencyKey: IdempotencyKey, accountId: UUID, amount: Amount) = JournalEntry(
            idempotencyKey.value, EntryType.WITHDRAWAL,
            listOf(
                JournalLine(LedgerAccount.CUSTOMER_DEPOSIT, accountId, Direction.DEBIT, amount.value),
                JournalLine(LedgerAccount.CASH, null, Direction.CREDIT, amount.value),
            ),
        )

        // 당행 송금: 예금(빚)이 A에서 B로 옮겨 갈 뿐, 현금은 등장하지 않는다
        fun transfer(idempotencyKey: IdempotencyKey, from: UUID, to: UUID, amount: Amount) = JournalEntry(
            idempotencyKey.value, EntryType.TRANSFER,
            listOf(
                JournalLine(LedgerAccount.CUSTOMER_DEPOSIT, from, Direction.DEBIT, amount.value),
                JournalLine(LedgerAccount.CUSTOMER_DEPOSIT, to, Direction.CREDIT, amount.value),
            ),
        )
    }
}
