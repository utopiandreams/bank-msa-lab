package lab.bank.transfer.application

import lab.bank.transfer.FakeCoreConfiguration
import lab.bank.transfer.FakeCoreInterbankPort
import lab.bank.transfer.FakeExternalBankPort
import lab.bank.transfer.FakeTransferEventPublisher
import lab.bank.transfer.TestcontainersConfiguration
import lab.bank.transfer.application.port.out.CoreTransferResult
import lab.bank.transfer.application.port.out.ExternalTransferResult
import lab.bank.transfer.application.port.out.InterbankTransferRepository
import lab.bank.transfer.domain.Amount
import lab.bank.transfer.domain.BankCode
import lab.bank.transfer.domain.IdempotencyConflictException
import lab.bank.transfer.domain.IdempotencyKey
import lab.bank.transfer.domain.InterbankTransferStatus
import lab.bank.transfer.domain.InterbankTransferStatus.CANCELLED
import lab.bank.transfer.domain.InterbankTransferStatus.COMPLETED
import lab.bank.transfer.domain.InterbankTransferStatus.WITHDRAW_CANCEL_REQUESTED
import lab.bank.transfer.domain.InterbankTransferStatus.EXTERNAL_BANK_REQUESTED
import lab.bank.transfer.domain.InterbankTransferStatus.FAILED
import lab.bank.transfer.domain.InterbankTransferStatus.WITHDRAW_REQUESTED
import lab.bank.transfer.domain.InvalidBankCodeException
import lab.bank.transfer.domain.UserId
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
import kotlin.test.assertTrue

/**
 * 타행 사가 테스트: 진짜 DB(Testcontainers) + 가짜 core 타행 포트 + 가짜 외부 결제망 + 가짜 발행기.
 * 결과는 use case가 돌려준 객체가 아니라 **DB에서 다시 읽어** 본다 (실제로 커밋된 상태가 기준).
 * 클래스에 @Transactional을 붙이지 않는다: "호출 전에 의도가 커밋됐나"를 봐야 해서 (TransferUseCaseTest와 같은 이유).
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class, FakeCoreConfiguration::class)
class InterbankTransferUseCaseTest {

    @Autowired lateinit var useCase: InterbankTransferUseCase
    @Autowired lateinit var repository: InterbankTransferRepository
    @Autowired lateinit var core: FakeCoreInterbankPort
    @Autowired lateinit var externalBank: FakeExternalBankPort
    @Autowired lateinit var events: FakeTransferEventPublisher

    private val requester = UserId("chulsoo")
    private val from = UUID.randomUUID()
    private val bank = BankCode("004")

    @BeforeEach
    fun setUp() {
        core.reset()
        externalBank.reset()
        events.reset()
    }

    private fun request(key: String = "key-${UUID.randomUUID()}", accountNumber: String = "123-456", amount: Long = 10_000) =
        useCase.transfer(requester, IdempotencyKey(key), from, bank, accountNumber, Amount(amount))

    private fun reload(id: UUID) = assertNotNull(repository.findById(id))

    private fun publishedStatuses(id: UUID): List<InterbankTransferStatus> =
        events.publishedInterbank.filter { it.transferId == id }.map { it.status }

    // ───────────── 끝까지 가는 경로 ─────────────

    @Test
    fun `성공 - 출금 → 외부 수락 → COMPLETED, 출금 전표가 남고 이벤트는 한 번`() {
        val withdrawEntry = UUID.randomUUID()
        core.withdrawRespond = { CoreTransferResult.Completed(withdrawEntry) }

        val result = request()

        val saved = reload(result.transfer.id)
        assertEquals(COMPLETED, saved.status)
        assertEquals(withdrawEntry, saved.coreEntryId)
        assertFalse(result.replayed)
        assertEquals(1, externalBank.calls.size)
        assertTrue(core.cancelCalls.isEmpty())
        assertEquals(listOf(COMPLETED), publishedStatuses(saved.id))
    }

    @Test
    fun `출금 거절 - FAILED, 외부로 보내지 않고 이벤트는 한 번`() {
        core.withdrawRespond = { CoreTransferResult.Rejected("INSUFFICIENT_BALANCE") }

        val result = request()

        val saved = reload(result.transfer.id)
        assertEquals(FAILED, saved.status)
        assertEquals("INSUFFICIENT_BALANCE", saved.failureCode)
        assertTrue(externalBank.calls.isEmpty())
        assertEquals(listOf(FAILED), publishedStatuses(saved.id))
    }

    @Test
    fun `외부 거절 → 보상 → CANCELLED, 취소 전표와 외부 사유가 남고 이벤트는 한 번`() {
        val cancelEntry = UUID.randomUUID()
        externalBank.respond = { ExternalTransferResult.Rejected("ACCOUNT_NOT_FOUND") }
        core.cancelRespond = { CoreTransferResult.Completed(cancelEntry) }

        val result = request()

        val saved = reload(result.transfer.id)
        assertEquals(CANCELLED, saved.status)
        assertEquals(cancelEntry, saved.cancelEntryId)
        assertEquals("ACCOUNT_NOT_FOUND", saved.externalRejectReason)
        assertEquals(listOf(saved.id), core.cancelCalls)   // 취소는 송금 ID로 원래 출금을 가리킨다
        assertEquals(listOf(CANCELLED), publishedStatuses(saved.id))
    }

    // ───────────── 결과 불명: 의도 상태에 머문다 ─────────────

    @Test
    fun `출금 결과 불명 - WITHDRAW_REQUESTED에 머물고 외부로 보내지 않는다, 이벤트 없음`() {
        core.withdrawRespond = { CoreTransferResult.Unknown("read timeout") }

        val result = request()

        assertEquals(WITHDRAW_REQUESTED, reload(result.transfer.id).status)
        assertTrue(externalBank.calls.isEmpty())
        assertTrue(publishedStatuses(result.transfer.id).isEmpty())
    }

    // 면접 단골: 결과 불명인데 보상하면 이중 지급 (상대 은행엔 입금됐는데 고객 돈도 돌려줌, pitfalls 27)
    @Test
    fun `외부 결과 불명 - 보상하지 않는다, EXTERNAL_BANK_REQUESTED에 머문다`() {
        externalBank.respond = { ExternalTransferResult.Unknown("read timeout") }

        val result = request()

        assertEquals(EXTERNAL_BANK_REQUESTED, reload(result.transfer.id).status)
        assertTrue(core.cancelCalls.isEmpty())
        assertTrue(publishedStatuses(result.transfer.id).isEmpty())
    }

    @Test
    fun `보상 결과 불명 - WITHDRAW_CANCEL_REQUESTED에 머문다 (보상은 실패로 끝나지 않고 재시도 대상), 이벤트 없음`() {
        externalBank.respond = { ExternalTransferResult.Rejected("ACCOUNT_NOT_FOUND") }
        core.cancelRespond = { CoreTransferResult.Unknown("503") }

        val result = request()

        assertEquals(WITHDRAW_CANCEL_REQUESTED, reload(result.transfer.id).status)
        assertTrue(publishedStatuses(result.transfer.id).isEmpty())
    }

    // ───────────── 의도 커밋 순서 ─────────────

    @Test
    fun `외부 호출 순간에는 의도(EXTERNAL_BANK_REQUESTED)가 이미 커밋돼 있고, 트랜잭션은 열려 있지 않다`() {
        var statusSeenByOthers: InterbankTransferStatus? = null
        var txOpen: Boolean? = null
        externalBank.onCall = { command ->
            statusSeenByOthers = repository.findById(command.transferId)?.status   // 다른 트랜잭션에서 읽는다 = 커밋됐나
            txOpen = TransactionSynchronizationManager.isActualTransactionActive()
        }

        request()

        assertEquals(EXTERNAL_BANK_REQUESTED, statusSeenByOthers)
        assertEquals(false, txOpen)
    }

    // ───────────── 멱등 ─────────────

    @Test
    fun `같은 키 재요청 - 현재 상태 그대로, 원격 호출 없음, 이벤트 다시 안 나감`() {
        val first = request(key = "same-key-${UUID.randomUUID()}")
        val key = first.transfer.idempotencyKey.value
        core.reset(); externalBank.reset()
        val publishedBefore = events.publishedInterbank.size

        val again = request(key = key)

        assertTrue(again.replayed)
        assertEquals(first.transfer.id, again.transfer.id)
        assertEquals(COMPLETED, again.transfer.status)
        assertTrue(core.withdrawCalls.isEmpty())
        assertTrue(externalBank.calls.isEmpty())
        assertEquals(publishedBefore, events.publishedInterbank.size)
    }

    @Test
    fun `같은 키에 다른 내용 - 409`() {
        val key = "same-key-${UUID.randomUUID()}"
        request(key = key, accountNumber = "123-456")

        assertFailsWith<IdempotencyConflictException> { request(key = key, accountNumber = "999-999") }
    }

    // ───────────── 입구 검증 ─────────────

    @Test
    fun `우리 은행 코드로 타행 송금 - 400, 아무것도 저장·호출하지 않는다`() {
        assertFailsWith<InvalidBankCodeException> {
            useCase.transfer(requester, IdempotencyKey("key-${UUID.randomUUID()}"), from, BankCode.OURS, "123-456", Amount(10_000))
        }
        assertTrue(core.withdrawCalls.isEmpty())
    }
}
