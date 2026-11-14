package lab.bank.transfer.api

import lab.bank.transfer.domain.IdempotencyConflictException
import lab.bank.transfer.domain.InvalidAmountException
import lab.bank.transfer.domain.InvalidIdempotencyKeyException
import lab.bank.transfer.domain.MissingUserException
import lab.bank.transfer.domain.SameAccountsException
import lab.bank.transfer.domain.TransferException
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * transfer 업무 예외 → HTTP 응답 (RFC 9457 Problem Details + code 속성, core와 같은 형식).
 *
 * IllegalArgumentException/IllegalStateException(도메인의 require/check)은 여기서 매핑하지 않는다:
 * 그건 입구 검증이 샌 우리 버그라 500으로 떠야 보인다. 400으로 바꾸면 버그가 "고객 잘못"으로 묻힌다.
 */
@RestControllerAdvice
class TransferExceptionHandler {

    @ExceptionHandler(TransferException::class)
    fun handle(e: TransferException): ProblemDetail = problem(
        when (e) {   // sealed: 예외를 추가하고 매핑을 빠뜨리면 컴파일 에러
            is InvalidAmountException -> HttpStatus.BAD_REQUEST
            is SameAccountsException -> HttpStatus.BAD_REQUEST
            is InvalidIdempotencyKeyException -> HttpStatus.BAD_REQUEST
            is MissingUserException -> HttpStatus.UNAUTHORIZED
            // 요청 형식은 맞지만 먼저 처리된 송금과 충돌 (core의 멱등 충돌과 같은 409)
            is IdempotencyConflictException -> HttpStatus.CONFLICT
        },
        e.message, e.code,
    )

    private fun problem(status: HttpStatus, detail: String?, code: String): ProblemDetail =
        ProblemDetail.forStatusAndDetail(status, detail ?: "").apply { setProperty("code", code) }
}
