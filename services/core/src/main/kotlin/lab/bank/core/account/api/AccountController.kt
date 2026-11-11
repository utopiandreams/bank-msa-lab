package lab.bank.core.account.api

import lab.bank.core.account.application.AccountUseCase
import lab.bank.core.account.application.UserId
import lab.bank.core.account.domain.Account
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

@RestController
@RequestMapping("/accounts")
class AccountController(
    private val accountUseCase: AccountUseCase,
) {

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@RequestBody request: CreateAccountRequest): AccountResponse =
        AccountResponse.from(accountUseCase.create(UserId(request.userId)))

    @GetMapping("/{accountId}")
    fun get(@PathVariable accountId: UUID): AccountResponse =
        AccountResponse.from(accountUseCase.get(accountId))
}

data class CreateAccountRequest(
    val userId: String,   // gateway가 생기면 바디 대신 X-User-Id 헤더에서 꺼낸다
)

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
