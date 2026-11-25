package lab.bank.reconciliation.domain

import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// transfer ↔ core 판정 규칙 (ADR-0006). 정상은 null, 안 맞으면 종류
class TransferCoreMatcherTest {

    private val t0 = Instant.parse("2026-11-25T01:00:00Z")

    private fun transfer(kind: String, status: String, amount: Long = 10_000, id: UUID = UUID.randomUUID()) =
        TransferRow(id, kind, amount, status, t0, t0, null, null, null)

    private fun entry(transferId: UUID, type: String, amount: Long = 10_000, reverses: UUID? = null, cancel: Boolean = false) =
        CoreEntryRow(UUID.randomUUID(), transferId.toString() + if (cancel) "-cancel" else "", type, amount, t0, reverses)

    private fun single(transfers: List<TransferRow>, entries: List<CoreEntryRow>, carried: Map<String, CarrySnapshot> = emptyMap()) =
        TransferCoreMatcher.match(transfers, entries, carried).single()

    @Test
    fun `당행 완료 + 송금 전표 하나 = 정상`() {
        val t = transfer("DOMESTIC", "COMPLETED")
        assertNull(single(listOf(t), listOf(entry(t.transferId, "TRANSFER"))).kind)
    }

    @Test
    fun `타행 취소 = 출금 + 그 출금을 가리키는 취소 = 정상`() {
        val t = transfer("INTERBANK", "CANCELLED")
        val w = entry(t.transferId, "INTERBANK_WITHDRAWAL")
        assertNull(single(listOf(t), listOf(w, entry(t.transferId, "INTERBANK_WITHDRAWAL_CANCEL", reverses = w.entryId, cancel = true))).kind)
    }

    @Test
    fun `실패한 송금은 전표가 없어야 정상`() {
        assertNull(single(listOf(transfer("DOMESTIC", "FAILED")), emptyList()).kind)
    }

    @Test
    fun `완료인데 전표가 없으면 MISSING_ENTRY - 이월 대상 (즉시 아님)`() {
        val e = single(listOf(transfer("DOMESTIC", "COMPLETED")), emptyList())
        assertEquals(ItemKind.MISSING_ENTRY, e.kind)
        assertTrue(!e.immediate)
    }

    @Test
    fun `실패라고 했는데 돈이 움직였으면 UNEXPECTED_ENTRY - 즉시 경보 (고객에겐 실패, 원장엔 출금)`() {
        val t = transfer("DOMESTIC", "FAILED")
        val e = single(listOf(t), listOf(entry(t.transferId, "TRANSFER")))
        assertEquals(ItemKind.UNEXPECTED_ENTRY, e.kind)
        assertTrue(e.immediate)
    }

    @Test
    fun `송금 기록이 없는 전표 - 전표 쪽에서 출발해야 찾는다 (양방향)`() {
        val orphan = entry(UUID.randomUUID(), "TRANSFER")
        val e = single(emptyList(), listOf(orphan))
        assertEquals(ItemKind.UNEXPECTED_ENTRY, e.kind)
    }

    @Test
    fun `송금 ID가 아닌 키의 전표도 송금 없는 전표로 잡는다 (core 직접 호출)`() {
        val e = single(emptyList(), listOf(CoreEntryRow(UUID.randomUUID(), "k6-whatever", "TRANSFER", 1, t0, null)))
        assertEquals(ItemKind.UNEXPECTED_ENTRY, e.kind)
        assertEquals("entry:k6-whatever", e.key)
    }

    @Test
    fun `금액이 다르면 MISMATCH`() {
        val t = transfer("INTERBANK", "COMPLETED", amount = 30_000)
        assertEquals(ItemKind.MISMATCH, single(listOf(t), listOf(entry(t.transferId, "INTERBANK_WITHDRAWAL", amount = 3_000))).kind)
    }

    @Test
    fun `취소가 다른 출금을 가리키면 MISMATCH`() {
        val t = transfer("INTERBANK", "CANCELLED")
        val w = entry(t.transferId, "INTERBANK_WITHDRAWAL")
        val e = single(listOf(t), listOf(w, entry(t.transferId, "INTERBANK_WITHDRAWAL_CANCEL", reverses = UUID.randomUUID(), cancel = true)))
        assertEquals(ItemKind.MISMATCH, e.kind)
    }

    @Test
    fun `끝나지 않은 송금은 판정하지 않고 PENDING`() {
        val t = transfer("INTERBANK", "WITHDRAW_CANCEL_REQUESTED")
        assertEquals(ItemKind.PENDING, single(listOf(t), listOf(entry(t.transferId, "INTERBANK_WITHDRAWAL"))).kind)
    }

    @Test
    fun `이월 - 어제 보상 중이던 송금이 오늘 취소로 끝나 다시 실리면, 어제 본 출금과 오늘 취소를 합쳐 정상`() {
        val yesterday = transfer("INTERBANK", "WITHDRAW_CANCEL_REQUESTED")
        val w = entry(yesterday.transferId, "INTERBANK_WITHDRAWAL")
        val carried = mapOf(yesterday.transferId.toString() to CarrySnapshot(yesterday, listOf(w)))

        val today = yesterday.copy(status = "CANCELLED", updatedAt = t0.plusSeconds(86_400))
        val cancel = entry(yesterday.transferId, "INTERBANK_WITHDRAWAL_CANCEL", reverses = w.entryId, cancel = true)

        assertNull(single(listOf(today), listOf(cancel), carried).kind)
    }

    @Test
    fun `이월 - 자정 경계로 어제 송금의 전표가 오늘 파일에 나오면 짝이 맞는다`() {
        val t = transfer("DOMESTIC", "COMPLETED")
        val carried = mapOf(t.transferId.toString() to CarrySnapshot(t, emptyList()))
        assertNull(single(emptyList(), listOf(entry(t.transferId, "TRANSFER")), carried).kind)
    }
}
