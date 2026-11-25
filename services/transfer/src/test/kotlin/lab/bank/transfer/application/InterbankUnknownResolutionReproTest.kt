package lab.bank.transfer.application

import lab.bank.transfer.FakeCoreConfiguration
import lab.bank.transfer.FakeCoreInterbankPort
import lab.bank.transfer.FakeCorePostingPort
import lab.bank.transfer.FakeExternalBankPort
import lab.bank.transfer.TestcontainersConfiguration
import lab.bank.transfer.application.port.out.InterbankTransferRepository
import lab.bank.transfer.domain.Amount
import lab.bank.transfer.domain.BankCode
import lab.bank.transfer.domain.IdempotencyKey
import lab.bank.transfer.domain.InterbankTransfer
import lab.bank.transfer.domain.InterbankTransferStatus
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
import kotlin.test.assertNotEquals

/**
 * 8주 차 2부 재현: **타행 송금이 사가 중간 상태에 멈추면 아무도 풀지 않는다.** (`docs/design-drills/08-unknown-resolution.md` 2부)
 *
 * 1부(TransferUnknownResolutionReproTest)와 같은 방식: 해결 뒤의 기대 동작을 적는다. 지금은 해소 일꾼이 당행만 보므로 실패 = 재현.
 * 해소 일꾼 한 회차(resolveStuck)가 타행도 풀어야 한다.
 *
 * 해결할 때 보강할 것:
 *   - 외부 조회 포트가 생기면 2번 테스트에서 조회 결과(성공 / 거절 / 없음)를 지정하고 기대값을 나눈다
 *   - 출금 확인(core 조회)이 필요한 WITHDRAW_REQUESTED 테스트는 core 타행 조회 포트가 생긴 뒤
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class, FakeCoreConfiguration::class)
class InterbankUnknownResolutionReproTest {

    @Autowired lateinit var resolutionUseCase: TransferResolutionUseCase
    @Autowired lateinit var repository: InterbankTransferRepository
    @Autowired lateinit var fakeCore: FakeCoreInterbankPort
    @Autowired lateinit var fakeCorePosting: FakeCorePostingPort
    @Autowired lateinit var fakeExternal: FakeExternalBankPort

    @BeforeEach
    fun setUp() {
        fakeCore.reset()
        fakeCorePosting.reset()
        fakeExternal.reset()
    }

    /** 그 상태가 된 지 한참 지난(= 그 홉에 보낸 지 한참 지난) 타행 송금. 출금 뒤 상태는 출금 전표 ID가 있다 */
    private fun stuck(status: InterbankTransferStatus): InterbankTransfer {
        val longAgo = Instant.now().minus(Duration.ofMinutes(10))
        val withdrawn = status != InterbankTransferStatus.WITHDRAW_REQUESTED
        return repository.save(
            InterbankTransfer(
                id = UUID.randomUUID(),
                requesterId = UserId("chulsoo"),
                idempotencyKey = IdempotencyKey("ib-${UUID.randomUUID()}"),
                fromAccountId = UUID.randomUUID(),
                toAccountId = "110-${UUID.randomUUID().toString().take(8)}",
                toBankCode = BankCode("004"),
                amount = Amount(30_000),
                status = status,
                failureCode = null,
                coreEntryId = if (withdrawn) UUID.randomUUID() else null,
                cancelEntryId = null,
                externalRejectReason = if (status == InterbankTransferStatus.WITHDRAW_CANCEL_REQUESTED) "RECEIVING_BANK_REJECTED" else null,
                createdAt = longAgo,
                updatedAt = longAgo,
                version = null,
            ),
        )
    }

    private fun reload(t: InterbankTransfer) = repository.findById(t.id)!!

    /** 출금은 됐고 외부엔 확실히 안 보냄 → 외부 송금을 고객 없이 늦게 실행하지 않고, 출금을 취소한다 */
    @Test
    fun `재현 - WITHDRAWN에 멈춘 타행 송금은 출금 취소로 끝나야 하는데 그대로 남는다`() {
        val t = stuck(InterbankTransferStatus.WITHDRAWN)

        resolutionUseCase.resolveStuck()

        assertEquals(listOf(t.id), fakeCore.cancelCalls.filter { it == t.id }, "출금 취소를 한 번 요청해야 한다")
        assertEquals(InterbankTransferStatus.CANCELLED, reload(t).status)
        assertEquals(0, fakeExternal.calls.count { it.transferId == t.id }, "외부 송금 재전송 금지")
    }

    /** 외부에 보냈을 수도 있음 → 외부 조회로 확정해야 한다. 어느 쪽이든 이 상태에 계속 남으면 안 된다 */
    @Test
    fun `재현 - EXTERNAL_BANK_REQUESTED에 멈춘 타행 송금은 외부 조회로 확정돼야 하는데 그대로 남는다`() {
        val t = stuck(InterbankTransferStatus.EXTERNAL_BANK_REQUESTED)

        resolutionUseCase.resolveStuck()

        assertNotEquals(InterbankTransferStatus.EXTERNAL_BANK_REQUESTED, reload(t).status)
        assertEquals(0, fakeExternal.calls.count { it.transferId == t.id }, "외부 송금 재전송 금지 (확정은 조회로)")
    }

    /** 보상 의도에 멈춤 → 보상은 확인이 아니라 성공할 때까지 재시도 */
    @Test
    fun `재현 - 출금 취소 요청 중에 멈춘 타행 송금은 취소를 재시도해 CANCELLED가 돼야 하는데 그대로 남는다`() {
        val t = stuck(InterbankTransferStatus.WITHDRAW_CANCEL_REQUESTED)

        resolutionUseCase.resolveStuck()

        assertEquals(listOf(t.id), fakeCore.cancelCalls.filter { it == t.id })
        assertEquals(InterbankTransferStatus.CANCELLED, reload(t).status)
    }
}
