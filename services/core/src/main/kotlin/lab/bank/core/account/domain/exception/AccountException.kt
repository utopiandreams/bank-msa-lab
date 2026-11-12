package lab.bank.core.account.domain.exception

import java.util.UUID

/**
 * account 모듈의 업무 예외. "버그"가 아니라 "정상적인 업무 실패"를 나타낸다.
 *
 * sealed: 하위 클래스가 이 파일 안에서만 생긴다 → 예외를 HTTP 응답으로 바꿀 때 `when`이 모든 경우를
 * 다뤘는지 컴파일러가 검사해 준다. 예외를 새로 추가하고 응답 매핑을 빠뜨리면 컴파일 에러.
 *
 * code: 클라이언트(transfer 등)가 메시지 문자열이 아니라 코드로 분기하게 한다. 메시지는 바뀌어도 코드는 안 바뀐다.
 */
sealed class AccountException(
    val code: String,
    message: String,
) : RuntimeException(message)

class AccountNotFoundException(accountId: UUID) :
    AccountException("ACCOUNT_NOT_FOUND", "계좌를 찾을 수 없습니다: $accountId")

// 잔액은 메시지에 넣지 않는다: 메시지는 로그·응답으로 퍼지기 쉽고, 잔액은 민감 정보다
class InsufficientBalanceException(accountId: UUID, amount: Long) :
    AccountException("INSUFFICIENT_BALANCE", "잔고가 부족합니다: 계좌 $accountId, 요청 금액 ${amount}원")
