package lab.bank.bankmock.api

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import lab.bank.bankmock.domain.InterbankOutcome
import lab.bank.bankmock.domain.InterbankTransfer
import lab.bank.bankmock.domain.RejectReason

data class InterbankTransferRequest(
    @field:NotBlank val toBankCode: String,
    @field:NotBlank val toAccountNumber: String,
    @field:Positive val amount: Long,
)

data class InterbankTransferResponse(
    val idempotencyKey: String,
    val outcome: InterbankOutcome,
    val rejectReason: RejectReason?,
) {
    companion object {
        fun from(t: InterbankTransfer) = InterbankTransferResponse(t.idempotencyKey, t.outcome, t.rejectReason)
    }
}

data class ErrorResponse(val code: String, val message: String)
