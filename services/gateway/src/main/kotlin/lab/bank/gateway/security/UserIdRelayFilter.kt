package lab.bank.gateway.security

import org.springframework.cloud.gateway.filter.GatewayFilterChain
import org.springframework.cloud.gateway.filter.GlobalFilter
import org.springframework.core.Ordered
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono

/**
 * 사용자 전달 필터 (ADR-0002 결정 3). 모든 라우트에 적용되는 GlobalFilter.
 *
 * 1. 들어온 X-User-Id는 무조건 지운다 → 고객이 붙인 값은 절대 서비스에 닿지 않는다
 * 2. 검증된 토큰의 sub를 X-User-Id로 새로 붙인다
 *
 * WebFlux 포인트: 인증 정보(principal)도 "나중에 나오는 값"(Mono)이라 바로 꺼낼 수 없다.
 * Mono를 이어 붙여(map → flatMap) "값이 나오면 요청을 고쳐서 다음 필터로" 를 선언한다. 블로킹(.block())은 금지.
 */
@Component
class UserIdRelayFilter : GlobalFilter, Ordered {

    override fun filter(exchange: ServerWebExchange, chain: GatewayFilterChain): Mono<Void> =
        exchange.getPrincipal<JwtAuthenticationToken>()      // Mono<JwtAuthenticationToken>: Security가 검증하고 넣어 둔 것
            .map { auth -> withUserId(exchange, auth.token.subject) }
            .defaultIfEmpty(withUserId(exchange, null))      // 인증 정보가 없으면(지금 구성에선 401로 막혀 안 옴) 지우기만
            .flatMap { chain.filter(it) }                     // 고친 요청으로 다음 필터 → 결국 transfer 호출

    // 요청은 불변이다. 바꾸려면 mutate()로 복사본을 만든다
    private fun withUserId(exchange: ServerWebExchange, userId: String?): ServerWebExchange =
        exchange.mutate()
            .request { req ->
                req.headers { h ->
                    h.remove(USER_ID_HEADER)
                    if (userId != null) h.set(USER_ID_HEADER, userId)
                }
            }
            .build()

    // 라우팅 필터들보다 먼저 돈다 (숫자가 작을수록 먼저). 헤더를 고친 뒤 전달되어야 하므로
    override fun getOrder(): Int = Ordered.HIGHEST_PRECEDENCE

    companion object {
        const val USER_ID_HEADER = "X-User-Id"
    }
}
