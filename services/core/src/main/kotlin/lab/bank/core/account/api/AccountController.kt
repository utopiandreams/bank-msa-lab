package lab.bank.core.account.api

import lab.bank.core.account.application.AccountUseCase
import lab.bank.core.account.application.UserId
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
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
