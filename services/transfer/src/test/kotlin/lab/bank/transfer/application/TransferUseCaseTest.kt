package lab.bank.transfer.application

import lab.bank.transfer.FakeCoreConfiguration
import lab.bank.transfer.FakeCorePostingPort
import lab.bank.transfer.TestcontainersConfiguration
import lab.bank.transfer.application.port.out.CoreTransferResult
import lab.bank.transfer.application.port.out.TransferRepository
import lab.bank.transfer.domain.Amount
import lab.bank.transfer.domain.IdempotencyKey
import lab.bank.transfer.domain.SameAccountsException
import lab.bank.transfer.domain.TransferStatus
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 송금 use case 테스트: 진짜 DB(Testcontainers) + 가짜 core.
 *
 * 테스트 클래스에 @Transactional을 붙이지 않는다 (core와 같은 이유): 붙이면 테스트 전체가 한 트랜잭션이 되어
 * "core 호출 전에 PROCESSING이 커밋됐나", "호출 중에 트랜잭션이 없나"를 검증할 수 없다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class, FakeCoreConfiguration::class)
class TransferUseCaseTest {

    @Autowired lateinit var transferUseCase: TransferUseCase
    @Autowired lateinit var transferRepository: TransferRepository
    @Autowired lateinit var fakeCore: FakeCorePostingPort

    private val from = UUID.randomUUID()
    private val to = UUID.randomUUID()

    @BeforeEach
    fun setUp() = fakeCore.reset()

    // 매번 새 멱등 키 = 서로 다른 송금 의도 (멱등 동작 자체는 TransferIdempotencyTest)
    private fun transfer(from: UUID, to: UUID, amount: Amount) =
        transferUseCase.transfer(IdempotencyKey("key-${UUID.randomUUID()}"), from, to, amount).transfer

    // DB에서 다시 읽는다: use case가 돌려준 객체가 아니라 실제로 커밋된 상태를 본다
    private fun reload(id: UUID) = assertNotNull(transferRepository.findById(id))

    // ───────────── 결과별 상태 ─────────────

    @Test
    fun `core 완료 - COMPLETED, core 원장 ID가 남는다`() {
        val entryId = UUID.randomUUID()
        fakeCore.respond = { CoreTransferResult.Completed(entryId) }

        val result = transfer(from, to, Amount(10_000))

        val saved = reload(result.id)
        assertEquals(TransferStatus.COMPLETED, saved.status)
        assertEquals(entryId, saved.coreEntryId)
        assertNull(saved.failureCode)
        assertEquals(1L, saved.version)   // 저장 두 번 (처리중 → 완료): 0 → 1
    }

    @Test
    fun `core 거절 - FAILED, 실패 사유가 남는다`() {
        fakeCore.respond = { CoreTransferResult.Rejected("INSUFFICIENT_BALANCE") }

        val result = transfer(from, to, Amount(10_000))

        val saved = reload(result.id)
        assertEquals(TransferStatus.FAILED, saved.status)
        assertEquals("INSUFFICIENT_BALANCE", saved.failureCode)
        assertNull(saved.coreEntryId)
    }

    @Test
    fun `core 결과 불명 - UNKNOWN, FAILED로 확정하지 않는다`() {
        fakeCore.respond = { CoreTransferResult.Unknown("read timeout") }

        val result = transfer(from, to, Amount(10_000))

        val saved = reload(result.id)
        assertEquals(TransferStatus.UNKNOWN, saved.status)
        assertNull(saved.coreEntryId)
        assertNull(saved.failureCode)
    }

    @Test
    fun `use case가 돌려준 송금은 DB 상태와 같다 - 컨트롤러가 이걸로 응답한다`() {
        val result = transfer(from, to, Amount(10_000))

        val saved = reload(result.id)
        assertEquals(saved.status, result.status)
        assertEquals(saved.version, result.version)
    }

    // ───────────── 트랜잭션 경계 (이 use case의 핵심) ─────────────

    @Test
    fun `core를 호출하는 동안 DB 트랜잭션이 열려 있지 않다 - 커넥션을 쥔 채 기다리지 않는다`() {
        var transactionActiveDuringCall: Boolean? = null
        fakeCore.onCall = { transactionActiveDuringCall = TransactionSynchronizationManager.isActualTransactionActive() }

        transfer(from, to, Amount(10_000))

        assertFalse(assertNotNull(transactionActiveDuringCall), "core 호출이 트랜잭션 안에서 일어났다")
    }

    @Test
    fun `core를 호출하는 시점에 PROCESSING이 이미 커밋돼 있다 - 호출 중 죽어도 흔적이 남는다`() {
        var statusSeenByOthers: TransferStatus? = null
        // 호출 순간 DB를 따로 읽는다 = 다른 트랜잭션(8주 차 해소 스케줄러)이 보는 상태
        fakeCore.onCall = { transfer -> statusSeenByOthers = transferRepository.findById(transfer.id)?.status }

        transfer(from, to, Amount(10_000))

        assertEquals(TransferStatus.PROCESSING, statusSeenByOthers)
    }

    @Test
    fun `core에는 저장된 송금 ID를 보낸다 - 그 ID가 core 쪽 멱등 키다`() {
        // 상태는 호출 순간에 기록한다: Transfer는 가변이라 calls에 담긴 객체는 호출 뒤 use case가 COMPLETED로 바꾼다
        var statusAtCall: TransferStatus? = null
        fakeCore.onCall = { statusAtCall = it.status }

        val result = transfer(from, to, Amount(10_000))

        assertEquals(1, fakeCore.calls.size)
        assertEquals(result.id, fakeCore.calls.single().id)
        assertEquals(TransferStatus.PROCESSING, statusAtCall)
    }

    // ───────────── 입구 검증 ─────────────

    @Test
    fun `같은 계좌로 송금 - 업무 예외, core를 부르지 않는다`() {
        assertFailsWith<SameAccountsException> { transfer(from, from, Amount(10_000)) }

        assertTrue(fakeCore.calls.isEmpty())
    }
}
