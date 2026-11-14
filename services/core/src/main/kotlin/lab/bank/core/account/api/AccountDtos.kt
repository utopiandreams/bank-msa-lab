package lab.bank.core.account.api

import lab.bank.core.account.domain.Account
import java.time.Instant
import java.util.UUID

// 엔티티를 그대로 응답하지 않는다: 내부 필드(version 등) 노출, 지연 로딩 문제
data class AccountResponse(
    val accountId: UUID,
    val ownerId: String,
    val balance: Long,
    val updatedAt: Instant,
) {
    companion object {
        fun from(account: Account) = AccountResponse(
            accountId = account.id,
            ownerId = account.ownerId,
            balance = account.balance,
            updatedAt = account.updatedAt,
        )
    }
}
