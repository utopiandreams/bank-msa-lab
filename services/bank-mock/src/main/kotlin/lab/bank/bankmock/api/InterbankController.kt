package lab.bank.bankmock.api

import jakarta.validation.Valid
import lab.bank.bankmock.application.IdempotencyConflictException
import lab.bank.bankmock.application.InterbankTransferService
import lab.bank.bankmock.application.ProcessingDeadlineExceededException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
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

    /**
     * 조회 (8주 차 계약). "없음"도 404가 아니라 200 + NOT_FOUND: 404는 URL 오타(엔드포인트 없음)와 구분이 안 된다 (core 상태 조회와 같은 이유).
     * 보내는 쪽은 처리 상한이 지난 뒤에만 묻는다 → 그때의 NOT_FOUND는 "앞으로도 처리하지 않음"으로 믿어도 된다
     */
    @GetMapping("/transfers/{idempotencyKey}")
    fun lookup(@PathVariable idempotencyKey: String): InterbankLookupResponse =
        service.find(idempotencyKey)
            ?.let { InterbankLookupResponse.found(it) }
            ?: InterbankLookupResponse.notFound(idempotencyKey)

    // 처리 상한을 넘겨 포기: "판정 못 함"이라 5xx (거절이 아님). 보내는 쪽은 결과 불명으로 다룬다
    @ExceptionHandler(ProcessingDeadlineExceededException::class)
    fun deadlineExceeded(e: ProcessingDeadlineExceededException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(ErrorResponse("PROCESSING_DEADLINE_EXCEEDED", e.message ?: ""))

    @ExceptionHandler(IdempotencyConflictException::class)
    fun conflict(e: IdempotencyConflictException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.CONFLICT).body(ErrorResponse("IDEMPOTENCY_CONFLICT", e.message ?: ""))
}
