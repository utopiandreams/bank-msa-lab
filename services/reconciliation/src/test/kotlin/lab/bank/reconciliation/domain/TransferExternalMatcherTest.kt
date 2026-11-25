package lab.bank.reconciliation.domain

import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// 타행 송금 ↔ 외부 정산 파일 판정표 (ADR-0006 결정 7, 개발자 작성)
class TransferExternalMatcherTest {

    private val t0 = Instant.parse("2026-11-25T01:00:00Z")

    private fun transfer(status: String, reject: String? = null, amount: Long = 30_000, kind: String = "INTERBANK") =
        TransferRow(UUID.randomUUID(), kind, amount, status, t0, t0, "004", "110-1234", reject)

    private fun ext(t: TransferRow, accepted: Boolean, amount: Long = t.amount, account: String = "110-1234") =
        ExternalRow(t.transferId.toString(), "004", account, amount, accepted, if (accepted) null else "RECEIVING_BANK_REJECTED", t0)

    private fun single(transfers: List<TransferRow>, external: List<ExternalRow>, carried: Map<String, CarrySnapshot> = emptyMap()) =
        TransferExternalMatcher.match(transfers, external, carried).single()

    @Test
    fun `완료 + 외부 입금 = 정상`() {
        val t = transfer("COMPLETED")
        assertNull(single(listOf(t), listOf(ext(t, true))).kind)
    }

    @Test
    fun `외부 거절로 취소 + 외부 거절 = 정상`() {
        val t = transfer("CANCELLED", reject = "RECEIVING_BANK_REJECTED")
        assertNull(single(listOf(t), listOf(ext(t, false))).kind)
    }

    @Test
    fun `조회 '없음'으로 취소 + 외부에도 없음 = 정상`() {
        assertNull(single(listOf(transfer("CANCELLED")), emptyList()).kind)
    }

    @Test
    fun `조회 '없음'으로 취소했는데 외부는 입금 = 이중 지급, 즉시 경보 (외부가 기록을 잃었다 나타남)`() {
        val t = transfer("CANCELLED")
        val e = single(listOf(t), listOf(ext(t, true)))
        assertEquals(ItemKind.DOUBLE_PAYMENT, e.kind)
        assertTrue(e.immediate)
    }

    @Test
    fun `외부 거절로 취소했는데 외부는 입금 = 이중 지급`() {
        val t = transfer("CANCELLED", reject = "RECEIVING_BANK_REJECTED")
        assertEquals(ItemKind.DOUBLE_PAYMENT, single(listOf(t), listOf(ext(t, true))).kind)
    }

    @Test
    fun `완료라고 했는데 외부는 거절 = 고객 손실, 즉시 경보`() {
        val t = transfer("COMPLETED")
        val e = single(listOf(t), listOf(ext(t, false)))
        assertEquals(ItemKind.CUSTOMER_LOSS, e.kind)
        assertTrue(e.immediate)
    }

    @Test
    fun `완료인데 외부 기록 없음 = 이월 (외부 처리일이 하루 늦을 수 있다)`() {
        val e = single(listOf(transfer("COMPLETED")), emptyList())
        assertEquals(ItemKind.MISSING_EXTERNAL, e.kind)
        assertTrue(!e.immediate)
    }

    @Test
    fun `받는 계좌나 금액이 다르면 MISMATCH`() {
        val t = transfer("COMPLETED")
        assertEquals(ItemKind.MISMATCH, single(listOf(t), listOf(ext(t, true, account = "110-9999"))).kind)
    }

    @Test
    fun `우리 기록이 없는 외부 거래 = 이월 (시계 차이일 수 있다)`() {
        val ghost = transfer("COMPLETED")
        val e = single(emptyList(), listOf(ext(ghost, true)))
        assertEquals(ItemKind.EXTERNAL_ONLY, e.kind)
        assertTrue(!e.immediate)
    }

    @Test
    fun `당행 송금은 외부 대사 대상이 아니다`() {
        assertTrue(TransferExternalMatcher.match(listOf(transfer("COMPLETED", kind = "DOMESTIC")), emptyList(), emptyMap()).isEmpty())
    }

    @Test
    fun `이월 - 어제 외부 기록이 없던 완료 송금이 오늘 외부 파일에 나오면 정상`() {
        val t = transfer("COMPLETED")
        val carried = mapOf(t.transferId.toString() to CarrySnapshot(t))
        assertNull(single(emptyList(), listOf(ext(t, true)), carried).kind)
    }
}
