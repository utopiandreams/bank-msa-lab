package lab.bank.bankmock.api

import jakarta.validation.Valid
import lab.bank.bankmock.application.IdempotencyConflictException
import lab.bank.bankmock.application.InterbankTransferService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 외부 결제망 입구. 보내는 쪽(transfer)이 Idempotency-Key = 송금 ID로 부른다 (ADR-0004 결정 4).
 * 거절도 정상 응답(200 + REJECTED)이다: "판정했다"는 사실이 확실하므로. 4xx·5xx는 "판정 못 함"에만 쓴다
 * (보내는 쪽이 5xx를 결과 불명으로 다루기 때문에, 확실한 거절을 5xx로 주면 보상 대신 멈춰 버린다)
 */
@RestController
@RequestMapping("/interbank")
class InterbankController(private val service: InterbankTransferService) {

    @PostMapping("/transfers")
    fun transfer(
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @Valid @RequestBody request: InterbankTransferRequest,
    ): InterbankTransferResponse =
        InterbankTransferResponse.from(
            service.transfer(idempotencyKey, request.toBankCode, request.toAccountNumber, request.amount),
        )

    @ExceptionHandler(IdempotencyConflictException::class)
    fun conflict(e: IdempotencyConflictException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.CONFLICT).body(ErrorResponse("IDEMPOTENCY_CONFLICT", e.message ?: ""))
}
