package lab.bank.core.account.api

import lab.bank.core.account.application.AccountUseCase
import lab.bank.core.account.application.UserId
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/accounts")
class AccountController(
    private val accountUseCase: AccountUseCase,
) {

    // 예금주는 바디가 아니라 X-User-Id(gateway가 토큰의 sub로 덮어쓴 값)로 정한다.
    // 바디로 받으면 아무 이름으로나 계좌를 만들 수 있었다 (G-4 이전)
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@RequestHeader(UserId.HEADER, required = false) userId: String?): AccountResponse =
        AccountResponse.from(accountUseCase.create(UserId.fromHeader(userId)))

    // 주인만 조회. 남의 계좌는 404 (없는 계좌와 같은 응답)
    @GetMapping("/{accountId}")
    fun get(
        @RequestHeader(UserId.HEADER, required = false) userId: String?,
        @PathVariable accountId: UUID,
    ): AccountResponse =
        AccountResponse.from(accountUseCase.getOwned(accountId, UserId.fromHeader(userId)))
}
