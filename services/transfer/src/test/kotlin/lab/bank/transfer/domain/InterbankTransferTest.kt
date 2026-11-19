package lab.bank.transfer.domain

import lab.bank.transfer.domain.InterbankTransferStatus.CANCELLED
import lab.bank.transfer.domain.InterbankTransferStatus.COMPLETED
import lab.bank.transfer.domain.InterbankTransferStatus.EXTERNAL_BANK_REJECTED
import lab.bank.transfer.domain.InterbankTransferStatus.EXTERNAL_BANK_REQUESTED
import lab.bank.transfer.domain.InterbankTransferStatus.FAILED
import lab.bank.transfer.domain.InterbankTransferStatus.REQUESTED
import lab.bank.transfer.domain.InterbankTransferStatus.WITHDRAWN
import lab.bank.transfer.domain.InterbankTransferStatus.WITHDRAW_REQUESTED
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// 타행 송금 상태 머신 (순수 도메인, 스프링 없음). 사가 유스케이스는 이 전이 규칙을 믿고 포트 결과만 연결한다
class InterbankTransferTest {

    private val from = UUID.randomUUID()

    private fun newTransfer(bankCode: String = "004", accountNumber: String = "123-456", amount: Long = 10_000) =
        InterbankTransfer.request(UserId("chulsoo"), IdempotencyKey("key-1"), from, BankCode(bankCode), accountNumber, Amount(amount))

    @Test
    fun `성공 - 출금 → 외부 송금 수락 → COMPLETED, 출금 전표가 남는다`() {
        val t = newTransfer()
        assertEquals(REQUESTED, t.status)

        val withdrawEntry = UUID.randomUUID()
        t.withdrawRequested()
        t.withdrawn(withdrawEntry)
        t.externalBankRequested()
        t.completed()

        assertEquals(COMPLETED, t.status)
        assertTrue(t.status.isFinal())
        assertEquals(withdrawEntry, t.coreEntryId)
        assertNull(t.cancelEntryId)
        assertNull(t.externalRejectReason)
    }

    @Test
    fun `출금 거절 - FAILED, core 사유는 failureCode에`() {
        val t = newTransfer()
        t.withdrawRequested()
        t.withdrawRejected("INSUFFICIENT_BALANCE")

        assertEquals(FAILED, t.status)
        assertTrue(t.status.isFinal())
        assertEquals("INSUFFICIENT_BALANCE", t.failureCode)
        assertNull(t.coreEntryId)
    }

    @Test
    fun `외부 거절 → 보상 → CANCELLED, 출금·취소 전표와 외부 사유가 모두 남는다`() {
        val t = newTransfer()
        val withdrawEntry = UUID.randomUUID()
        val cancelEntry = UUID.randomUUID()

        t.withdrawRequested()
        t.withdrawn(withdrawEntry)
        t.externalBankRequested()
        t.externalBankRejected("ACCOUNT_NOT_FOUND")
        assertEquals(EXTERNAL_BANK_REJECTED, t.status)
        assertFalse(t.status.isFinal())   // 보상이 끝나기 전엔 끝이 아니다 (이벤트 발행 안 함)

        t.cancelled(cancelEntry)

        assertEquals(CANCELLED, t.status)
        assertTrue(t.status.isFinal())
        assertEquals(withdrawEntry, t.coreEntryId)
        assertEquals(cancelEntry, t.cancelEntryId)
        assertEquals("ACCOUNT_NOT_FOUND", t.externalRejectReason)
        assertNull(t.failureCode)   // 외부 거절 사유는 출금 실패 사유와 섞이지 않는다
    }

    @Test
    fun `결과 불명이면 의도 상태에 그대로 머문다 (자기 자신으로 전이)`() {
        val t = newTransfer()
        t.withdrawRequested()
        t.withdrawRequested()   // 출금 결과 모름 → 재시도 대상
        assertEquals(WITHDRAW_REQUESTED, t.status)

        t.withdrawn(UUID.randomUUID())
        t.externalBankRequested()
        t.externalBankRequested()   // 외부 결과 모름 → 보상 금지, 조회 대상
        assertEquals(EXTERNAL_BANK_REQUESTED, t.status)
    }

    @Test
    fun `출금만 된 상태에서 바로 취소할 수 없다 - 외부 거절이 확인돼야 보상`() {
        val t = newTransfer()
        t.withdrawRequested()
        t.withdrawn(UUID.randomUUID())

        assertFailsWith<IllegalStateException> { t.cancelled(UUID.randomUUID()) }
        assertEquals(WITHDRAWN, t.status)
    }

    @Test
    fun `외부 결과 불명에서 보상으로 갈 수 없다 - 보냈을 수도 있다 (이중 지급 방지)`() {
        val t = newTransfer()
        t.withdrawRequested()
        t.withdrawn(UUID.randomUUID())
        t.externalBankRequested()

        assertFailsWith<IllegalStateException> { t.cancelled(UUID.randomUUID()) }
        assertEquals(EXTERNAL_BANK_REQUESTED, t.status)
    }

    @Test
    fun `출금 의도 없이 출금 완료로 갈 수 없다 - 호출 전에 의도를 커밋해야 한다`() {
        val t = newTransfer()
        assertFailsWith<IllegalStateException> { t.withdrawn(UUID.randomUUID()) }
    }

    @Test
    fun `끝 상태에서는 더 이상 바뀌지 않는다`() {
        val t = newTransfer()
        t.withdrawRequested()
        t.withdrawRejected("INSUFFICIENT_BALANCE")

        assertFailsWith<IllegalStateException> { t.withdrawRequested() }
        assertFailsWith<IllegalStateException> { t.externalBankRequested() }
        assertEquals(FAILED, t.status)
    }

    @Test
    fun `우리 은행 코드로는 타행 송금을 만들 수 없다`() {
        assertFailsWith<IllegalArgumentException> { newTransfer(bankCode = BankCode.OURS.value) }
    }

    @Test
    fun `은행 코드 형식이 틀리면 업무 예외 (400)`() {
        listOf("", "04", "0004", "abc").forEach {
            assertFailsWith<InvalidBankCodeException>("'$it'") { BankCode(it) }
        }
    }

    @Test
    fun `같은 요청 판정 - 은행 코드가 다르면 계좌번호가 같아도 다른 요청`() {
        val t = newTransfer(bankCode = "004", accountNumber = "123-456")

        assertTrue(t.isSameRequestAs(from, BankCode("004"), "123-456", Amount(10_000)))
        assertFalse(t.isSameRequestAs(from, BankCode("088"), "123-456", Amount(10_000)))
        assertFalse(t.isSameRequestAs(from, BankCode("004"), "123-457", Amount(10_000)))
        assertFalse(t.isSameRequestAs(from, BankCode("004"), "123-456", Amount(20_000)))
    }
}
