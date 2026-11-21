package lab.bank.core.account.application.balance

import lab.bank.core.account.application.UserId
import lab.bank.core.account.domain.Account
import lab.bank.core.account.domain.exception.AccountNotFoundException
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * 소유자 검사 (인가). 남의 계좌면 "없음"과 같은 예외를 던진다: 밖에서는 둘을 구분할 수 없어야
 * 아무 ID나 넣어 보며 계좌 존재 여부를 알아내는 것(enumeration)을 막는다. 구분은 로그에만 남긴다 (보안 감사용).
 * (docs/learning/pitfalls.md 1. BOLA, 2. 존재 여부 누출)
 *
 * 예금주는 바뀌지 않는다 → 잠금 없이 읽은 값으로 검사해도 경합이 없다 (ATOMIC_UPDATE가 이걸 이용한다)
 */
object AccountOwnership {

    private val log = LoggerFactory.getLogger(AccountOwnership::class.java)

    fun require(account: Account, requester: UserId): Account {
        require(account.id, account.ownerId, requester)
        return account
    }

    fun require(accountId: UUID, ownerId: String, requester: UserId) {
        if (ownerId != requester.value) {
            log.warn("계좌 소유자 불일치: account={}, requester={}", accountId, requester.value)
            throw AccountNotFoundException(accountId)
        }
    }
}
