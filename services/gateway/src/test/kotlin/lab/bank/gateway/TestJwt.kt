package lab.bank.gateway

import java.time.Instant
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 테스트용 JWT를 라이브러리 없이 손으로 만든다. JWT가 어떻게 생겼는지 그대로 보이게 하려는 것.
 *
 *   base64url(헤더) . base64url(페이로드) . base64url(HMAC-SHA256(앞의 두 조각, 비밀키))
 *
 * 헤더·페이로드는 암호화가 아니라 base64일 뿐이라 누구나 읽는다. 서명은 "안 바뀌었음"만 보장한다.
 * 그래서 위조·만료·alg 바꿔치기 같은 "잘못된 토큰"도 자유롭게 만들 수 있다.
 */
object TestJwt {

    const val SECRET = "test-secret-key-at-least-32-bytes-long!!"   // HS256은 256비트(32바이트) 이상
    const val ISSUER = "bank-gateway"

    private val b64 = Base64.getUrlEncoder().withoutPadding()

    fun hs256(
        sub: String,
        secret: String = SECRET,
        issuer: String = ISSUER,
        exp: Instant = Instant.now().plusSeconds(600),
    ): String {
        val header = b64.encodeToString("""{"alg":"HS256","typ":"JWT"}""".toByteArray())
        val payload = b64.encodeToString(
            """{"sub":"$sub","iss":"$issuer","iat":${Instant.now().epochSecond},"exp":${exp.epochSecond}}""".toByteArray()
        )
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret.toByteArray(), "HmacSHA256")) }
        val signature = b64.encodeToString(mac.doFinal("$header.$payload".toByteArray()))
        return "$header.$payload.$signature"
    }

    /** 고전적인 공격: 헤더를 alg=none으로 바꾸고 서명을 비운다. "서명 없음"을 받아 주는 구현이면 뚫린다 */
    fun algNone(sub: String): String {
        val header = b64.encodeToString("""{"alg":"none","typ":"JWT"}""".toByteArray())
        val payload = b64.encodeToString(
            """{"sub":"$sub","iss":"$ISSUER","exp":${Instant.now().plusSeconds(600).epochSecond}}""".toByteArray()
        )
        return "$header.$payload."
    }
}
