package lab.bank.core.account.domain

import lab.bank.core.account.domain.exception.InsufficientBalanceException
import lab.bank.core.shared.Amount
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

// 도메인 단위 테스트: Spring도 DB도 없이 Account 객체만으로 규칙을 검사한다 (빠르다, 밀리초)
// 쓸 만한 검증 함수 (kotlin.test): assertEquals(기대값, 실제값), assertFailsWith<예외타입> { 코드 }
//
// 0원·음수 입출금 테스트가 여기 없는 이유: Account가 Amount를 받으므로 그런 호출은 이제 만들 수조차 없다.
// 금액 규칙은 AmountTest가 검사한다 (규칙이 사는 곳에서 테스트한다)
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
            newAccount.deposit(Amount(10_000), UUID.randomUUID())
            assertEquals(10_000L, newAccount.balance)
        }

        @Test
        fun `입금하면 마지막 변경 시각과 원장 거래 ID가 갱신된다`() {
            val newAccount = newAccount()
            val beforeUpdateAt = newAccount.updatedAt
            val entryId = UUID.randomUUID()

            newAccount.deposit(Amount(10_000), entryId)

            assertNotEquals(beforeUpdateAt, newAccount.updatedAt)
            assertEquals(entryId, newAccount.lastLedgerId)
        }
    }

    @Nested
    @DisplayName("출금")
    inner class Withdraw {

        // 잔액이 들어 있는 계좌
        private fun accountWithBalance(balance: Long = 10_000L) =
            newAccount().apply { deposit(Amount(balance), UUID.randomUUID()) }

        @Test
        fun `잔액 안에서 출금하면 잔액이 줄어든다`() {
            val account = accountWithBalance(10_000L)
            account.withdraw(Amount(3_000), UUID.randomUUID())
            assertEquals(7_000L, account.balance)
        }

        @Test
        fun `잔액 전부를 출금할 수 있다`() {
            // 경계값: 잔액 == 출금액 → 성공, 잔액 0원 (balance < amount 를 <= 로 잘못 쓰면 여기서 걸린다)
            val account = accountWithBalance(10_000L)
            account.withdraw(Amount(10_000), UUID.randomUUID())
            assertEquals(0L, account.balance)
        }

        @Test
        fun `잔액보다 많이 출금하면 거절하고 잔액은 그대로다`() {
            // 경계값: 잔액 + 1원
            val account = accountWithBalance(10_000L)
            assertFailsWith<InsufficientBalanceException> { account.withdraw(Amount(10_001), UUID.randomUUID()) }
            assertEquals(10_000L, account.balance)   // 거절만이 아니라 잔액이 안 바뀌었는지까지
        }
    }
}
