package lab.bank.transfer.application

import lab.bank.transfer.FakeCoreConfiguration
import lab.bank.transfer.FakeCorePostingPort
import lab.bank.transfer.FakeTransferEventPublisher
import lab.bank.transfer.TestcontainersConfiguration
import lab.bank.transfer.application.port.out.CoreTransferResult
import lab.bank.transfer.domain.Amount
import lab.bank.transfer.domain.IdempotencyKey
import lab.bank.transfer.domain.TransferStatus
import lab.bank.transfer.domain.UserId
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 송금 이벤트 발행 시점 (ADR-0003 결정 2): **끝 상태로 바뀌는 순간에만, 한 번.**
 * 응답할 때마다가 아니라 상태가 바뀔 때. 그래야 재요청에 알림이 중복되지 않는다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class, FakeCoreConfiguration::class)
class TransferEventPublishingTest {

    @Autowired lateinit var transferUseCase: TransferUseCase
    @Autowired lateinit var fakeCore: FakeCorePostingPort
    @Autowired lateinit var publisher: FakeTransferEventPublisher

    private val requester = UserId("chulsoo")
    private val from = UUID.randomUUID()
    private val to = UUID.randomUUID()

    @BeforeEach
    fun setUp() {
        fakeCore.reset()
        publisher.reset()
    }

    private fun request(key: IdempotencyKey) = transferUseCase.transfer(requester, key, from, to, Amount(10_000)).transfer
    private fun newKey() = IdempotencyKey("key-${UUID.randomUUID()}")

    @Test
    fun `완료되면 COMPLETED 이벤트를 한 번 발행한다`() {
        val transfer = request(newKey())

        assertEquals(listOf(FakeTransferEventPublisher.Published(transfer.id, TransferStatus.COMPLETED, null)), publisher.published)
    }

    @Test
    fun `core가 거절하면 FAILED 이벤트도 발행한다 - 잔고 부족도 고객이 알아야 한다`() {
        fakeCore.respond = { CoreTransferResult.Rejected("INSUFFICIENT_BALANCE") }

        val transfer = request(newKey())

        assertEquals(listOf(FakeTransferEventPublisher.Published(transfer.id, TransferStatus.FAILED, "INSUFFICIENT_BALANCE")), publisher.published)
    }

    @Test
    fun `결과 불명이면 발행하지 않는다`() {
        fakeCore.respond = { CoreTransferResult.Unknown("timeout") }

        request(newKey())

        assertTrue(publisher.published.isEmpty())
    }

    @Test
    fun `결과 불명이었다가 재요청으로 완료되면 그때 발행한다`() {
        val key = newKey()
        fakeCore.respond = { CoreTransferResult.Unknown("timeout") }
        val first = request(key)
        fakeCore.respond = { CoreTransferResult.Completed(UUID.randomUUID()) }

        request(key)   // 같은 키 재요청 → 같은 송금 ID로 core 재전송 → 완료

        assertEquals(listOf(FakeTransferEventPublisher.Published(first.id, TransferStatus.COMPLETED, null)), publisher.published)
    }

    @Test
    fun `이미 끝난 송금의 재요청은 다시 발행하지 않는다 - 알림 중복 방지`() {
        val key = newKey()
        request(key)

        request(key)   // 200 + 기존 송금 (replay)
        request(key)

        assertEquals(1, publisher.published.size)
    }
}
