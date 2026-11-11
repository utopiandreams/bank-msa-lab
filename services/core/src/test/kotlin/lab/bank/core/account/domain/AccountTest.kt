package lab.bank.core.account.domain

import lab.bank.core.account.domain.exception.InsufficientBalanceException
import lab.bank.core.account.domain.exception.InvalidAmountException
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

// 도메인 단위 테스트: Spring도 DB도 없이 Account 객체만으로 규칙을 검사한다 (빠르다, 밀리초)
// 쓸 만한 검증 함수 (kotlin.test): assertEquals(기대값, 실제값), assertFailsWith<예외타입> { 코드 }
class AccountTest {

    // 테스트마다 새 계좌. 필요하면 잔액을 채우는 헬퍼를 만든다 (예: 10_000원 들어 있는 계좌)
    private fun newAccount() = Account(ownerId = "u-1")

    @Test
    fun `새 계좌는 잔액 0원으로 시작한다`() {
        val newAccount = newAccount()
        assertEquals(0L, newAccount.balance)
    }

    @Nested
    @DisplayName("입금")
    inner class Deposit {

        @Test
        fun `입금하면 잔액이 늘어난다`() {
            val newAccount = newAccount()
            val depositAmount = 10_000L
            newAccount.deposit(depositAmount)
            assertEquals(10_000L, newAccount.balance)
        }

        @Test
        fun `0원 입금은 거절한다`() {
            val newAccount = newAccount()
            val depositAmount = 0L
            assertFailsWith<InvalidAmountException> { newAccount.deposit(depositAmount) }
        }

        @Test
        fun `음수 금액 입금은 거절한다`() {
            val newAccount = newAccount()
            val depositAmount = -10_000L
            assertFailsWith<InvalidAmountException> { newAccount.deposit(depositAmount) }
        }

        @Test
        fun `입금하면 마지막 변경 시각이 갱신된다`() {
            val newAccount = newAccount()
            val depositAmount = 10_000L
            val beforeUpdateAt = newAccount.updatedAt
            newAccount.deposit(depositAmount)
            val afterUpdateAt = newAccount.updatedAt
            assertNotEquals(beforeUpdateAt, afterUpdateAt)
        }
    }

    @Nested
    @DisplayName("출금")
    inner class Withdraw {

        // 10_000원 들어 있는 계좌
        private fun accountWithBalance(balance: Long = 10_000L) = newAccount().apply { deposit(balance) }

        @Test
        fun `잔액 안에서 출금하면 잔액이 줄어든다`() {
            val account = accountWithBalance(10_000L)
            account.withdraw(3_000L)
            assertEquals(7_000L, account.balance)
        }

        @Test
        fun `잔액 전부를 출금할 수 있다`() {
            // 경계값: 잔액 == 출금액 → 성공, 잔액 0원 (balance < amount 를 <= 로 잘못 쓰면 여기서 걸린다)
            val account = accountWithBalance(10_000L)
            account.withdraw(10_000L)
            assertEquals(0L, account.balance)
        }

        @Test
        fun `잔액보다 많이 출금하면 거절하고 잔액은 그대로다`() {
            // 경계값: 잔액 + 1원
            val account = accountWithBalance(10_000L)
            assertFailsWith<InsufficientBalanceException> { account.withdraw(10_001L) }
            assertEquals(10_000L, account.balance)   // 거절만이 아니라 잔액이 안 바뀌었는지까지
        }

        @Test
        fun `0원 출금은 거절한다`() {
            val account = accountWithBalance(10_000L)
            assertFailsWith<InvalidAmountException> { account.withdraw(0L) }
            assertEquals(10_000L, account.balance)
        }

        @Test
        fun `음수 금액 출금은 거절한다`() {
            // 리뷰의 🔴 케이스: withdraw(-1_000_000)으로 돈이 생기면 안 된다
            val account = accountWithBalance(10_000L)
            assertFailsWith<InvalidAmountException> { account.withdraw(-1_000_000L) }
            assertEquals(10_000L, account.balance)
        }
    }
}
