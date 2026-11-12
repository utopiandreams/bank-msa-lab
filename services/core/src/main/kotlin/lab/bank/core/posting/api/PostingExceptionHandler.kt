package lab.bank.core.posting.api

import lab.bank.core.ledger.domain.exception.IdempotencyConflictException
import lab.bank.core.ledger.domain.exception.LedgerException
import lab.bank.core.posting.application.exception.PostingException
import lab.bank.core.posting.application.exception.SameAccountTransferException
import lab.bank.core.shared.InvalidAmountException
import lab.bank.core.shared.InvalidIdempotencyKeyException
import lab.bank.core.shared.SharedException
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * 거래 API에서 나는 업무 예외 → HTTP 응답 (RFC 9457 Problem Details).
 * AccountException(계좌 없음 404, 잔액 부족 422)은 AccountExceptionHandler가 맡는다 (전역 advice).
 *
 * ledger·shared 예외를 여기서 매핑하는 이유: 그 모듈들은 HTTP 입구가 없고, 거래 API가 유일한 입구라서.
 * shared를 쓰는 API가 더 생기면 shared 전용 핸들러로 뺀다.
 */
@RestControllerAdvice
class PostingExceptionHandler {

    // 클라이언트가 요청을 고치면 해결된다 → 400
    @ExceptionHandler(SharedException::class)
    fun handle(e: SharedException): ProblemDetail = problem(
        when (e) {   // sealed: 예외를 추가하고 매핑을 빠뜨리면 컴파일 에러
            is InvalidAmountException -> HttpStatus.BAD_REQUEST
            is InvalidIdempotencyKeyException -> HttpStatus.BAD_REQUEST
        },
        e.message, e.code,
    )

    @ExceptionHandler(PostingException::class)
    fun handle(e: PostingException): ProblemDetail = problem(
        when (e) {
            is SameAccountTransferException -> HttpStatus.BAD_REQUEST
        },
        e.message, e.code,
    )

    // 같은 키에 다른 내용: 요청 형식은 맞지만 서버의 현재 상태(먼저 처리된 거래)와 충돌 → 409
    @ExceptionHandler(LedgerException::class)
    fun handle(e: LedgerException): ProblemDetail = problem(
        when (e) {
            is IdempotencyConflictException -> HttpStatus.CONFLICT
        },
        e.message, e.code,
    )

    private fun problem(status: HttpStatus, detail: String?, code: String): ProblemDetail =
        ProblemDetail.forStatusAndDetail(status, detail ?: "").apply { setProperty("code", code) }
}
