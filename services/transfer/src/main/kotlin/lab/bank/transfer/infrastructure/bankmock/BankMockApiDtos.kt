package lab.bank.transfer.infrastructure.bankmock

// bank-mock API 형식 (원본: services/bank-mock/.../api/BankMockDtos.kt). 코드를 공유하지 않고 JSON 계약만 맞춘다 (ACL)

data class BankMockTransferRequest(
    val toBankCode: String,
    val toAccountNumber: String,
    val amount: Long,
)

// GET /interbank/transfers/{송금 ID} 응답. status: ACCEPTED | REJECTED | NOT_FOUND (문자열로 받는 이유는 아래와 같다)
data class BankMockLookupResponse(
    val idempotencyKey: String?,
    val status: String?,
    val rejectReason: String?,
)

// outcome: ACCEPTED | REJECTED. 문자열로 받는다: 외부가 값을 추가해도 역직렬화가 깨지지 않게 (모르는 값은 어댑터가 Unknown으로)
data class BankMockTransferResponse(
    val idempotencyKey: String?,
    val outcome: String?,
    val rejectReason: String?,
)
