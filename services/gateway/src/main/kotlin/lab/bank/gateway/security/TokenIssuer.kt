package lab.bank.gateway.security

import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.JwsHeader
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.stereotype.Component
import java.time.Clock

/**
 * 학습용 토큰 발급기 (ADR-0002 결정 1: 의도한 타협, 비밀번호 검증 없음).
 * 실무에서는 이 역할을 인증 서버(IdP)가 맡고 gateway는 검증만 한다.
 *
 * 페이로드는 서명될 뿐 암호화되지 않는다 → 누구나 읽을 수 있으니 민감 정보(계좌번호 등)를 넣지 않는다.
 */
@Component
class TokenIssuer(
    private val encoder: JwtEncoder,
    private val props: JwtProperties,
    private val clock: Clock = Clock.systemUTC(),
) {

    fun issue(userId: String): IssuedToken {
        val now = clock.instant()
        val claims = JwtClaimsSet.builder()
            .issuer(props.issuer)
            .subject(userId)                    // 누구인가 → gateway가 X-User-Id로 넘긴다
            .issuedAt(now)
            .expiresAt(now.plus(props.ttl))
            .build()
        val header = JwsHeader.with(MacAlgorithm.HS256).build()
        val token = encoder.encode(JwtEncoderParameters.from(header, claims)).tokenValue
        return IssuedToken(token, props.ttl.seconds)
    }
}
