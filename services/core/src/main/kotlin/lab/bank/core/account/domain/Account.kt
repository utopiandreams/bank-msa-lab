package lab.bank.core.account.domain

import jakarta.persistence.*
import lab.bank.core.account.domain.exception.InsufficientBalanceException
import lab.bank.core.account.domain.exception.InvalidAmountException
import java.time.Instant
import java.util.*

@Entity
@Table(name = "accounts", schema = "account")   // 모듈별 스키마 분리 (ledger는 ledger 스키마)
class Account(
    ownerId: String,
) {
    // 계좌 ID: DB가 아니라 앱이 만든다. 저장 전에도 ID가 있어서 원장·이벤트에 바로 쓸 수 있다
    @Id
    val id: UUID = UUID.randomUUID()

    // 예금주 ID (gateway가 생기면 X-User-Id 헤더의 값)
    @Column(nullable = false, updatable = false)
    val ownerId: String = ownerId

    // 잔액 (원 단위). 밖에서는 읽기만, 변경은 도메인 메서드로만
    @Column(nullable = false)
    var balance: Long = 0
        protected set

    // 마지막 원장 ID (티켓 B에서 원장을 남길 때 채운다)
    var lastLedgerId: UUID? = null
        protected set

    // 마지막 변경 시각
    @Column(nullable = false)
    var updatedAt: Instant = Instant.now()
        protected set

    // 버전 (7주 차 낙관적 락). 또 하나의 역할: ID를 앱이 만들기 때문에 Spring Data는
    // "version이 null이면 새 엔티티"로 판단해 INSERT한다 (null이 아니면 SELECT 후 merge)
    @Version
    var version: Long? = null
        protected set

    fun withdraw(amount: Long) {
        if (amount <= 0) { throw InvalidAmountException(amount) }
        if (balance < amount) { throw InsufficientBalanceException(id, amount) }
        balance -= amount
        updatedAt = Instant.now()
    }

    fun deposit(amount: Long) {
        if (amount <= 0) { throw InvalidAmountException(amount) }
        balance += amount
        updatedAt = Instant.now()
    }

}