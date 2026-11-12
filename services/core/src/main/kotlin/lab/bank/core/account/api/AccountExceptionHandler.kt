package lab.bank.core.account.api

import lab.bank.core.account.domain.exception.AccountException
import lab.bank.core.account.domain.exception.AccountNotFoundException
import lab.bank.core.account.domain.exception.InsufficientBalanceException
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

// 업무 예외 → HTTP 응답 (RFC 9457 Problem Details 형식)
// 도메인은 HTTP를 모르고, HTTP 상태 코드를 정하는 건 api 계층의 몫이다
// 전역 advice: 계좌 예외는 account API뿐 아니라 거래 API(posting)에서도 난다 (없는 계좌로 입금, 잔액 부족 출금)
@RestControllerAdvice
class AccountExceptionHandler {

    @ExceptionHandler(AccountException::class)
    fun handle(e: AccountException): ProblemDetail {
        val status = when (e) {   // sealed라서 else가 필요 없다. 예외를 추가하면 여기서 컴파일 에러
            is AccountNotFoundException -> HttpStatus.NOT_FOUND
            is InsufficientBalanceException -> HttpStatus.UNPROCESSABLE_CONTENT   // 요청 형식은 맞지만 업무 규칙상 불가 (422)
        }
        return ProblemDetail.forStatusAndDetail(status, e.message ?: "").apply {
            setProperty("code", e.code)
        }
    }
}
