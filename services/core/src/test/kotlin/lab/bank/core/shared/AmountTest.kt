package lab.bank.core.shared

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

// 금액 규칙은 여기 한 곳에서 검사한다. Amount를 받는 모든 곳(posting·account·ledger)은 이 규칙을 믿고 다시 검사하지 않는다
class AmountTest {

    @Test
    fun `1원 이상이면 만들어진다`() {
        assertEquals(1L, Amount(1).value)   // 경계값
    }

    @Test
    fun `0원은 만들 수 없다`() {
        assertFailsWith<InvalidAmountException> { Amount(0) }
    }

    @Test
    fun `음수는 만들 수 없다`() {
        // 음수 출금이 허용되면 출금으로 돈이 생긴다
        assertFailsWith<InvalidAmountException> { Amount(-1_000_000) }
    }

    @Test
    fun `같은 금액이면 같은 값이다`() {
        assertEquals(Amount(10_000), Amount(10_000))
    }
}
