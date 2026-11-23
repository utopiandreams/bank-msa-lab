package lab.bank.transfer.application

import lab.bank.transfer.FakeCoreConfiguration
import lab.bank.transfer.FakeCorePostingPort
import lab.bank.transfer.TestcontainersConfiguration
import lab.bank.transfer.application.port.out.CoreTransferLookupResult
import lab.bank.transfer.application.port.out.CoreTransferResult
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
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * 8주 차 재현: **당행 송금 결과 불명 해소** (ADR-0005, `docs/design-drills/08-unknown-resolution.md` 1부)
 *
 * 6주 차 TransferEventLossTest와 같은 방식: 테스트는 **해결 뒤의 기대 동작**을 적는다.
 * 지금 코드에서는 셋 다 실패한다 = 문제가 실제로 있다는 재현. 해결하면 초록이 된다.
 * 그래서 이 파일은 해결 커밋과 함께 push한다 (먼저 push하면 CI가 빨개진다).
 *
 * 규칙 (ADR-0005, 2026-11-23 확정):
 *   - core에 송금을 실행시키는 경로는 **처음 요청 하나뿐.** 재전송 없음 (고객 재요청도, 해소 일꾼도)
 *   - PROCESSING·UNKNOWN은 **createdAt(= core에 보낸 시각) + 5초**가 지났으면 core에 **조회**해서 확정
 *       조회 COMPLETED → COMPLETED / NOT_PROCESSED → FAILED(CORE_NOT_PROCESSED) / 조회 실패 → 그대로
 *     안 지났으면 지금 상태 그대로 돌려준다
 *   - 결과 쓰기 @Version 충돌에서 진 고객 요청은 다시 읽어서 그 상태로 돌려준다 (500 아님)
 *
 * 조회 포트(CorePostingPort.lookup)와 가짜(FakeCorePostingPort.lookupRespond)는 준비됨.
 * 세부 명세(5초 안/지남 × 조회 결과)는 TransferIdempotencyTest, 여기는 "왜 바꾸나"를 보여 주는 장면 셋.
 * 해소 일꾼(스케줄러) 테스트는 일꾼 클래스가 생긴 뒤 따로 (고객 없이도 풀리는지).
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class, FakeCoreConfiguration::class)
class TransferUnknownResolutionReproTest {

    @Autowired lateinit var transferUseCase: TransferUseCase
    @Autowired lateinit var transferRepository: TransferRepository
    @Autowired lateinit var fakeCore: FakeCorePostingPort

    private val chulsoo = UserId("chulsoo")
    private val from = UUID.randomUUID()
    private val to = UUID.randomUUID()

    @BeforeEach
    fun setUp() = fakeCore.reset()

    private fun newKey() = IdempotencyKey("key-${UUID.randomUUID()}")

    private fun request(key: IdempotencyKey) =
        transferUseCase.transfer(chulsoo, key, from, to, Amount(10_000))

    /** 이미 저장된 송금을 원하는 상태·나이로 만들어 둔다. createdAt = core에 보낸 시각 */
    private fun existing(key: IdempotencyKey, status: TransferStatus, age: Duration): Transfer {
        val sentAt = Instant.now().minus(age)
        return transferRepository.save(
            Transfer(UUID.randomUUID(), chulsoo, key, from, to, Amount(10_000), status, null, null, sentAt, sentAt, null),
        )
    }

    /**
     * 늦은 실행 (pitfalls 38).
     * 10시: 잔액 부족으로 core가 거절(롤백 = 흔적 없음)했는데 응답을 못 받아 UNKNOWN.
     * 12시: 그사이 월급이 들어왔고 고객이 같은 키로 다시 요청 → 지금은 같은 송금 ID로 **재전송** → core가 이번엔 처리.
     * 멱등은 "두 번 실행"만 막는다. 거절을 기억하지 않는 core에서는 재전송 = 2시간 전 의사로 지금 실행.
     */
    @Test
    fun `재현 - 결과 불명 송금에 같은 키 재요청이 core에 송금을 다시 실행시킨다 (늦은 실행)`() {
        val key = newKey()
        existing(key, TransferStatus.UNKNOWN, age = Duration.ofHours(2))
        fakeCore.respond = { CoreTransferResult.Completed(UUID.randomUUID()) }   // 지금 보내면 잔액이 있어서 처리된다
        fakeCore.lookupRespond = { CoreTransferLookupResult.NotProcessed }       // core엔 흔적이 없다 (10시 거절은 롤백됨)

        val result = request(key)

        assertEquals(0, fakeCore.calls.size, "재전송 금지: core 송금 API는 처음 요청에서 한 번만 불려야 한다 (확정은 조회로)")
        assertEquals(TransferStatus.FAILED, result.transfer.status, "흔적이 없으면 실패로 확정. 원하면 고객이 새 키로 지금 다시 보낸다")
    }

    /**
     * 멈춘 PROCESSING.
     * transfer가 PROCESSING 커밋 뒤 죽었다 (core에 보내기 전 / core 커밋 뒤 저장 전 / 타임아웃 뒤 저장 전).
     * 지금은 고객이 같은 키로 아무리 다시 와도 "PROCESSING 그대로"만 돌려준다 → 영원히 처리 중.
     * 해결 뒤: 보낸 지 5초가 지났으니 core에 조회해서 끝 상태로 확정한다.
     */
    @Test
    fun `재현 - 보낸 지 5초 지난 PROCESSING은 같은 키 재요청에도 영원히 PROCESSING으로 남는다`() {
        val key = newKey()
        existing(key, TransferStatus.PROCESSING, age = Duration.ofSeconds(10))
        fakeCore.lookupRespond = { CoreTransferLookupResult.Completed(UUID.randomUUID()) }   // core는 커밋했는데 저장 전에 죽은 경우

        val result = request(key)

        assertNotEquals(TransferStatus.PROCESSING, result.transfer.status, "5초가 지났으면 조회로 끝 상태가 돼야 한다")
        assertTrue(result.transfer.status.isFinal)
    }

    /**
     * 결과 쓰기 경합.
     * 같은 결과 불명 송금을 두 쪽이 동시에 확정하려 한다 (지금은 고객 재요청 둘, 해결 뒤엔 고객 재요청 + 해소 일꾼).
     * 둘 다 core에서 같은 결과를 받고 송금 행에 쓴다 → @Version이 하나만 통과시킨다 (이벤트 중복은 없음: 진 쪽은 outbox INSERT까지 롤백).
     * 지금은 진 쪽 예외를 아무도 안 잡아서 고객이 500을 받는다. 송금은 실제로 완료됐는데도.
     * 해결 뒤: 진 쪽은 다시 읽어서 이긴 쪽이 쓴 상태를 돌려준다.
     *
     * 둘 다 core에 들어올 때까지 붙잡아서 같은 version을 읽은 채로 응답을 받게 한다.
     * 지금 코드는 재전송(onCall), 해결 뒤엔 조회(onLookup)로 들어오므로 두 곳 모두에 같은 래치를 건다.
     */
    @Test
    fun `재현 - 같은 결과 불명 송금을 동시에 확정하면 진 쪽 고객 요청이 예외를 받는다`() {
        val key = newKey()
        existing(key, TransferStatus.UNKNOWN, age = Duration.ofSeconds(10))
        val bothInCore = CountDownLatch(2)
        val holdUntilBothArrive: (Transfer) -> Unit = {
            bothInCore.countDown()
            bothInCore.await(5, TimeUnit.SECONDS)
        }
        fakeCore.onCall = holdUntilBothArrive
        fakeCore.onLookup = holdUntilBothArrive
        fakeCore.lookupRespond = { CoreTransferLookupResult.Completed(UUID.randomUUID()) }

        val errors = Collections.synchronizedList(mutableListOf<Throwable>())
        val statuses = Collections.synchronizedList(mutableListOf<TransferStatus>())
        val pool = Executors.newFixedThreadPool(2)
        repeat(2) {
            pool.submit {
                runCatching { request(key) }
                    .onSuccess { statuses += it.transfer.status }
                    .onFailure { errors += it }
            }
        }
        pool.shutdown()
        assertTrue(pool.awaitTermination(15, TimeUnit.SECONDS))

        assertEquals(emptyList(), errors.map { it.javaClass.simpleName }, "진 쪽도 예외 없이 응답해야 한다")
        assertEquals(listOf(TransferStatus.COMPLETED, TransferStatus.COMPLETED), statuses.toList())
    }
}
