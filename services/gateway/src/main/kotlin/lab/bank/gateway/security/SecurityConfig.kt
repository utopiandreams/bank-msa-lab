package lab.bank.gateway.security

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.security.config.web.server.ServerHttpSecurity
import org.springframework.security.config.web.server.invoke
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.JwtValidators
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder
import org.springframework.security.web.server.SecurityWebFilterChain
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers.pathMatchers

/**
 * 인증 설정 (ADR-0002). WebFlux라서 Servlet용 HttpSecurity가 아니라 ServerHttpSecurity를 쓴다.
 *
 * 요청 순서: Security 필터(WebFilter) → gateway 라우팅·필터(GlobalFilter) → transfer
 *   토큰이 없거나 틀리면 Security가 401로 끝내서 gateway 필터까지 오지 않는다.
 */
@Configuration
@EnableConfigurationProperties(JwtProperties::class)
class SecurityConfig {

    @Bean
    fun securityFilterChain(http: ServerHttpSecurity): SecurityWebFilterChain = http {
        authorizeExchange {
            authorize(pathMatchers(HttpMethod.POST, "/auth/token"), permitAll)   // 학습용 발급 (비밀번호 없음)
            authorize("/actuator/health/**", permitAll)              // 쿠버네티스 프로브
            authorize(anyExchange, authenticated)                    // 나머지는 전부 토큰 필요 (모르는 경로도 401)
        }
        // 이 한 줄이: Authorization: Bearer 추출 → 서명 검증(아래 디코더) → exp·nbf·iss 검사 → 실패 시 401 + WWW-Authenticate
        oauth2ResourceServer { jwt { } }
        csrf { disable() }        // CSRF는 브라우저가 쿠키를 자동으로 붙이는 세션 방식의 문제. 토큰을 헤더로 직접 보내는 API라 해당 없음
        httpBasic { disable() }   // Security 기본값(아이디·비밀번호 팝업)을 끈다
        formLogin { disable() }
    }

    /**
     * 검증기. HS256 비밀키로 서명을 확인하고, 알고리즘을 HS256으로 고정한다.
     * 고정하지 않으면 "헤더의 alg를 믿는" 구현이 되어 alg=none 같은 바꿔치기에 뚫릴 수 있다.
     *
     * Keycloak(RS256)으로 바꿀 때는 이 빈만 NimbusReactiveJwtDecoder.withJwkSetUri(...)로 바꾼다.
     */
    @Bean
    fun jwtDecoder(props: JwtProperties): ReactiveJwtDecoder =
        NimbusReactiveJwtDecoder.withSecretKey(props.secretKey())
            .macAlgorithm(MacAlgorithm.HS256)
            .build()
            .apply { setJwtValidator(JwtValidators.createDefaultWithIssuer(props.issuer)) }   // 기본(exp·nbf, 시계 오차 60초) + iss

    /** 발급기. 같은 비밀키로 서명한다 (발급자 = 검증자 = gateway라 HS256이면 충분, ADR-0002 결정 2) */
    @Bean
    fun jwtEncoder(props: JwtProperties): JwtEncoder =
        NimbusJwtEncoder.withSecretKey(props.secretKey()).build()
}
