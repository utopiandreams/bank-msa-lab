package lab.bank.gateway.security

data class IssuedToken(
    val value: String,
    val expiresInSeconds: Long,
)
