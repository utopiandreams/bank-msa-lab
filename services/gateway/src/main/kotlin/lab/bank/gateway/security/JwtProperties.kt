package lab.bank.gateway.security

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * gateway.jwt.* 설정. 비밀키는 k8s Secret → 환경변수로만 들어온다 (Git에 없음).
 */
@ConfigurationProperties("gateway.jwt")
data class JwtProperties(
    val secret: String,
    val issuer: String,
    val ttl: Duration,
) {
    init {
        // HS256은 256비트(32바이트) 이상의 키가 필요하다. 짧으면 기동 자체를 막는다 (Nimbus도 거부하지만 메시지를 분명히)
        require(secret.toByteArray().size >= 32) { "gateway.jwt.secret은 32바이트 이상이어야 합니다" }
    }

    fun secretKey(): SecretKey = SecretKeySpec(secret.toByteArray(), "HmacSHA256")
}
