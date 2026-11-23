package lab.bank.transfer.application

import lab.bank.transfer.FakeCoreConfiguration
import lab.bank.transfer.FakeCorePostingPort
import lab.bank.transfer.FakeTransferEventPublisher
import lab.bank.transfer.TestcontainersConfiguration
import lab.bank.transfer.application.port.out.CoreTransferLookupResult
import lab.bank.transfer.application.port.out.TransferRepository
import lab.bank.transfer.domain.Amount
import lab.bank.transfer.domain.IdempotencyKey
import lab.bank.transfer.domain.Transfer
import lab.bank.transfer.domain.TransferStatus
import lab.bank.transfer.domain.UserId
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 해소 일꾼 한 회차 명세 (ADR-0005). resolveOne(TODO 개발자)을 구현하면 초록이 된다.
 *
 * 스케줄러는 테스트에서 꺼져 있다 (src/test/resources/config/application.yaml) → 회차를 직접 부른다.
 * DB를 공유하므로 다른 테스트가 남긴 오래된 행도 잡힐 수 있다 → 이 테스트의 행은 아주 옛날(2000년대)에 보낸 것으로 만들어
 * 오래된 순 맨 앞에 오게 하고, 가짜 core는 **이 테스트의 송금 ID에만** 답한다 (나머지는 조회 실패 → 회차가 거기서 끝남).
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class, FakeCoreConfiguration::class)
class TransferResolutionUseCaseTest {

    @Autowired lateinit var resolutionUseCase: TransferResolutionUseCase
    @Autowired lateinit var transferRepository: TransferRepository
    @Autowired lateinit var fakeCore: FakeCorePostingPort
    @Autowired lateinit var fakePublisher: FakeTransferEventPublisher

    private val answers = mutableMapOf<UUID, CoreTransferLookupResult>()

    @BeforeEach
    fun setUp() {
        fakeCore.reset()
        fakePublisher.reset()
        answers.clear()
        fakeCore.lookupRespond = { answers[it.id] ?: CoreTransferLookupResult.Unknown("이 테스트의 송금 아님") }
    }

    private var seq = 0L

    // 아주 옛날에 보낸 송금 (다른 테스트의 행보다 앞에 오게). 순서대로 1초씩 뒤
    private fun stuck(status: TransferStatus, answer: CoreTransferLookupResult): Transfer {
        val sentAt = Instant.parse("2000-01-01T00:00:00Z").plusSeconds(seq++)
        return transferRepository.save(
            Transfer(
                UUID.randomUUID(), UserId("chulsoo"), IdempotencyKey("r-${UUID.randomUUID()}"),
                UUID.randomUUID(), UUID.randomUUID(), Amount(1_000), status, null, null, sentAt, sentAt, null,
            ),
        ).also { answers[it.id] = answer }
    }

    private fun reload(t: Transfer) = transferRepository.findById(t.id)!!

    @Test
    fun `조회 COMPLETED - COMPLETED로 확정, 원장 ID, 완료 이벤트, 재전송 없음`() {
        val entryId = UUID.randomUUID()
        val t = stuck(TransferStatus.UNKNOWN, CoreTransferLookupResult.Completed(entryId))

        resolutionUseCase.resolveStuck()

        val after = reload(t)
        assertEquals(TransferStatus.COMPLETED, after.status)
        assertEquals(entryId, after.coreEntryId)
        assertTrue(fakePublisher.published.any { it.transferId == t.id && it.status == TransferStatus.COMPLETED })
        assertTrue(fakeCore.calls.isEmpty(), "재전송 금지")
    }

    @Test
    fun `조회 NOT_PROCESSED - FAILED(CORE_NOT_PROCESSED)로 확정, 실패 이벤트`() {
        val t = stuck(TransferStatus.PROCESSING, CoreTransferLookupResult.NotProcessed)

        resolutionUseCase.resolveStuck()

        val after = reload(t)
        assertEquals(TransferStatus.FAILED, after.status)
        assertEquals("CORE_NOT_PROCESSED", after.failureCode)
        assertTrue(fakePublisher.published.any { it.transferId == t.id && it.status == TransferStatus.FAILED })
    }

    @Test
    fun `조회 실패 - 끝 상태로 만들지 않고, 그 회차는 거기서 멈춘다 (core 장애면 나머지도 타임아웃만 기다림)`() {
        val first = stuck(TransferStatus.UNKNOWN, CoreTransferLookupResult.Unknown("read timeout"))
        val second = stuck(TransferStatus.UNKNOWN, CoreTransferLookupResult.Completed(UUID.randomUUID()))

        resolutionUseCase.resolveStuck()

        assertTrue(!reload(first).status.isFinal)
        assertEquals(TransferStatus.UNKNOWN, reload(second).status, "앞에서 조회가 실패하면 뒤는 다음 회차에")
        assertEquals(listOf(first.id), fakeCore.lookups.map { it.id })
    }

    @Test
    fun `보낸 지 기준 시간이 안 지난 송금은 조회하지 않는다 (core가 아직 처리 중일 수 있음)`() {
        val now = Instant.now()
        val young = transferRepository.save(
            Transfer(
                UUID.randomUUID(), UserId("chulsoo"), IdempotencyKey("r-${UUID.randomUUID()}"),
                UUID.randomUUID(), UUID.randomUUID(), Amount(1_000), TransferStatus.UNKNOWN, null, null,
                now.minus(Duration.ofSeconds(1)), now, null,
            ),
        )
        answers[young.id] = CoreTransferLookupResult.NotProcessed

        resolutionUseCase.resolveStuck()

        assertEquals(TransferStatus.UNKNOWN, reload(young).status)
        assertTrue(fakeCore.lookups.none { it.id == young.id })
    }
}
