package lab.bank.gateway.api

import lab.bank.gateway.security.TokenIssuer
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

/**
 * 학습용 토큰 발급 API. k6 부하·패널 시연에서 사용자별 토큰을 쉽게 뽑기 위한 것 (ADR-0002 결정 1).
 *
 * WebFlux에서도 @RestController를 그대로 쓸 수 있다. 서명은 메모리 안의 계산이라 논블로킹이고,
 * 그래서 Mono로 감쌀 필요 없이 값을 바로 돌려줘도 이벤트 루프를 막지 않는다.
 */
@RestController
class TokenController(
    private val tokenIssuer: TokenIssuer,
) {

    @PostMapping("/auth/token")
    fun issue(@RequestBody request: TokenRequest): TokenResponse {
        val userId = request.userId?.trim()
        if (userId.isNullOrEmpty() || userId.length > MAX_USER_ID_LENGTH) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "userId는 1~${MAX_USER_ID_LENGTH}자여야 합니다")
        }
        val token = tokenIssuer.issue(userId)
        return TokenResponse(access_token = token.value, token_type = "Bearer", expires_in = token.expiresInSeconds)
    }

    companion object {
        const val MAX_USER_ID_LENGTH = 64
    }
}
