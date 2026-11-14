package lab.bank.gateway.api

data class TokenRequest(
    val userId: String?,
)

// OAuth2 토큰 응답(RFC 6749)과 같은 필드 이름. 나중에 IdP로 바꿔도 클라이언트(k6, 패널)가 그대로 읽게
data class TokenResponse(
    val access_token: String,
    val token_type: String,
    val expires_in: Long,
)
