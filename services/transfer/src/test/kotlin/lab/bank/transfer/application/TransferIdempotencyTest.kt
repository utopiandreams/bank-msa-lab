package lab.bank.transfer.application

import lab.bank.transfer.FakeCoreConfiguration
import lab.bank.transfer.FakeCorePostingPort
import lab.bank.transfer.TestcontainersConfiguration
import lab.bank.transfer.application.port.out.CoreTransferLookupResult
import lab.bank.transfer.application.port.out.CoreTransferResult
import lab.bank.transfer.application.port.out.DuplicateIdempotencyKeyException
import lab.bank.transfer.application.port.out.TransferRepository
import lab.bank.transfer.domain.Amount
import lab.bank.transfer.domain.IdempotencyConflictException
import lab.bank.transfer.domain.IdempotencyKey
import lab.bank.transfer.domain.Transfer
import lab.bank.transfer.domain.TransferStatus
import lab.bank.transfer.domain.UserId
import lab.bank.transfer.infrastructure.persistence.TransferPersistenceAdapter
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 고객 멱등 키 명세 (T-2). TransferUseCaseImpl의 TODO(개발자)를 구현하면 전부 초록이 된다.
 *
 *   같은 키의 기존 송금이                  처리
 *   COMPLETED / FAILED                   그대로 반환 (replayed, core 호출 없음)
 *   PROCESSING / UNKNOWN, 보낸 지 5초 안   그대로 반환 (core가 아직 처리 중일 수 있어 조회도 안 함)
 *   PROCESSING / UNKNOWN, 5초 지남        core에 **조회** → 처리됨 COMPLETED / 안 됨 FAILED(CORE_NOT_PROCESSED) / 조회 실패 그대로
 *                                        **재전송 없음** (8주 차 ADR-0005, 이전 T-2: UNKNOWN이면 재전송)
 *   내용이 다름                           IdempotencyConflictException
 *   동시에 같은 키                        송금 1건, core 호출 1번
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class, FakeCoreConfiguration::class)
class TransferIdempotencyTest {

    @Autowired lateinit var transferUseCase: TransferUseCase
    @Autowired lateinit var transferRepository: TransferRepository
    @Autowired lateinit var fakeCore: FakeCorePostingPort
    @Autowired lateinit var jdbc: JdbcTemplate

    // 동시 요청 테스트에서만 save에 끼어든다. 다른 테스트에선 진짜 동작 그대로 (스파이 기본값 = 원래 빈에 위임)
    @MockitoSpyBean lateinit var repositorySpy: TransferPersistenceAdapter

    private val from = UUID.randomUUID()
    private val to = UUID.randomUUID()

    @BeforeEach
    fun setUp() = fakeCore.reset()

    private fun newKey() = IdempotencyKey("key-${UUID.randomUUID()}")

    private val chulsoo = UserId("chulsoo")

    private fun request(key: IdempotencyKey, amount: Long = 10_000, to: UUID = this.to, requester: UserId = chulsoo) =
        transferUseCase.transfer(requester, key, from, to, Amount(amount))

    // 이미 저장된 송금을 원하는 상태로 만들어 둔다 (PROCESSING·UNKNOWN은 정상 흐름으로는 멈춰 있게 만들기 어려워서)
    // age: 보낸 지 얼마나 됐나 (createdAt = core에 보낸 시각, ADR-0005 결정 1)
    private fun existing(key: IdempotencyKey, status: TransferStatus, amount: Long = 10_000, age: Duration = Duration.ZERO): Transfer {
        val sentAt = Instant.now().minus(age)
        return transferRepository.save(
            Transfer(UUID.randomUUID(), chulsoo, key, from, to, Amount(amount), status, null, null, sentAt, sentAt, null),
        )
    }

    private val stale = Duration.ofSeconds(10)   // 확인 기준 5초를 넘긴 나이

    private fun countByKey(key: IdempotencyKey): Int =
        jdbc.queryForObject("SELECT count(*) FROM transfer.transfer WHERE idempotency_key = ?", Int::class.java, key.value)!!

    // ───────────── 처음 ─────────────

    @Test
    fun `처음 온 키 - 새 송금, replayed 아님`() {
        val result = request(newKey())

        assertFalse(result.replayed)
        assertEquals(TransferStatus.COMPLETED, result.transfer.status)
        assertEquals(1, fakeCore.calls.size)
    }

    // ───────────── 키 범위: 요청자별 (Flyway V3) ─────────────

    // 재현 (2026-11-14 클러스터): 전역 키일 때 영희가 철수와 같은 키·같은 내용으로 보내 철수의 송금을 200으로 받았다
    @Test
    fun `다른 사용자가 같은 키·같은 내용 - 남의 송금을 돌려주지 않고 각자 새 송금`() {
        val key = newKey()
        val chulsoos = request(key)

        val youngheesResult = request(key, requester = UserId("younghee"))

        assertFalse(youngheesResult.replayed)
        assertTrue(chulsoos.transfer.id != youngheesResult.transfer.id)
        assertEquals(UserId("younghee"), youngheesResult.transfer.requesterId)
        assertEquals(2, fakeCore.calls.size)
    }

    // 재현 (2026-11-14 클러스터): 전역 키일 때 영희의 정상 송금이 철수의 키와 겹쳐 409로 거절됐다
    @Test
    fun `다른 사용자가 같은 키·다른 내용 - 409가 아니라 새 송금`() {
        val key = newKey()
        request(key)

        val result = request(key, amount = 99_000, requester = UserId("younghee"))

        assertFalse(result.replayed)
        assertEquals(TransferStatus.COMPLETED, result.transfer.status)
    }

    // ───────────── 끝 상태: 그대로 반환 ─────────────

    @Test
    fun `완료된 송금에 같은 키 - 같은 송금을 그대로, core는 다시 안 부른다 (이중 송금 방지)`() {
        val key = newKey()
        val first = request(key)

        val second = request(key)

        assertTrue(second.replayed)
        assertEquals(first.transfer.id, second.transfer.id)
        assertEquals(TransferStatus.COMPLETED, second.transfer.status)
        assertEquals(1, fakeCore.calls.size)
        assertEquals(1, countByKey(key))
    }

    @Test
    fun `실패한 송금에 같은 키 - FAILED 그대로, 다시 시도하지 않는다 (사유를 고친 재시도는 새 키)`() {
        val key = newKey()
        fakeCore.respond = { CoreTransferResult.Rejected("INSUFFICIENT_BALANCE") }
        val first = request(key)

        fakeCore.respond = { CoreTransferResult.Completed(UUID.randomUUID()) }   // 지금 보내면 성공할 상황이어도
        val second = request(key)

        assertTrue(second.replayed)
        assertEquals(first.transfer.id, second.transfer.id)
        assertEquals(TransferStatus.FAILED, second.transfer.status)
        assertEquals("INSUFFICIENT_BALANCE", second.transfer.failureCode)
        assertEquals(1, fakeCore.calls.size)
    }

    // ───────────── 처리 중·결과 불명, 보낸 지 5초 안: 그대로 반환 ─────────────

    @Test
    fun `처리 중인 송금에 같은 키 - 5초 안이면 PROCESSING 그대로, core에 송금도 조회도 안 한다`() {
        val key = newKey()
        val processing = existing(key, TransferStatus.PROCESSING)

        val result = request(key)

        assertTrue(result.replayed)
        assertEquals(processing.id, result.transfer.id)
        assertEquals(TransferStatus.PROCESSING, result.transfer.status)
        assertTrue(fakeCore.calls.isEmpty())
        assertTrue(fakeCore.lookups.isEmpty())
    }

    @Test
    fun `결과 불명 송금에 같은 키 - 5초 안이면 UNKNOWN 그대로 (core가 아직 처리 중일 수 있어 조회하면 오판)`() {
        val key = newKey()
        val unknown = existing(key, TransferStatus.UNKNOWN)

        val result = request(key)

        assertTrue(result.replayed)
        assertEquals(unknown.id, result.transfer.id)
        assertEquals(TransferStatus.UNKNOWN, result.transfer.status)
        assertTrue(fakeCore.calls.isEmpty())
        assertTrue(fakeCore.lookups.isEmpty())
    }

    // ───────────── 처리 중·결과 불명, 5초 지남: 조회로 확정 (재전송 없음) ─────────────

    @Test
    fun `결과 불명 5초 지남 - 조회해서 처리됐으면 COMPLETED, 같은 송금 ID로 묻고 재전송은 안 한다`() {
        val key = newKey()
        val unknown = existing(key, TransferStatus.UNKNOWN, age = stale)
        val entryId = UUID.randomUUID()
        fakeCore.lookupRespond = { CoreTransferLookupResult.Completed(entryId) }

        val result = request(key)

        assertTrue(result.replayed)
        assertEquals(unknown.id, fakeCore.lookups.single().id)
        assertTrue(fakeCore.calls.isEmpty(), "재전송 금지 (ADR-0005 결정 2)")
        assertEquals(TransferStatus.COMPLETED, result.transfer.status)
        assertEquals(entryId, result.transfer.coreEntryId)
        assertEquals(1, countByKey(key))
    }

    @Test
    fun `결과 불명 5초 지남 - 조회해서 안 됐으면 FAILED, 실패 코드 CORE_NOT_PROCESSED`() {
        val key = newKey()
        existing(key, TransferStatus.UNKNOWN, age = stale)
        fakeCore.lookupRespond = { CoreTransferLookupResult.NotProcessed }

        val result = request(key)

        assertEquals(TransferStatus.FAILED, result.transfer.status)
        assertEquals("CORE_NOT_PROCESSED", result.transfer.failureCode)
        assertTrue(fakeCore.calls.isEmpty())
    }

    @Test
    fun `결과 불명 5초 지남 - 조회도 실패하면 UNKNOWN 유지, 예외 없이 돌려준다`() {
        val key = newKey()
        val unknown = existing(key, TransferStatus.UNKNOWN, age = stale)
        fakeCore.lookupRespond = { CoreTransferLookupResult.Unknown("read timeout") }

        val result = request(key)

        assertTrue(result.replayed)
        assertEquals(unknown.id, result.transfer.id)
        assertEquals(TransferStatus.UNKNOWN, result.transfer.status)
    }

    @Test
    fun `처리 중 5초 지남 - 조회해서 처리됐으면 COMPLETED (core 커밋 뒤 저장 전에 죽은 경우)`() {
        val key = newKey()
        existing(key, TransferStatus.PROCESSING, age = stale)
        val entryId = UUID.randomUUID()
        fakeCore.lookupRespond = { CoreTransferLookupResult.Completed(entryId) }

        val result = request(key)

        assertEquals(TransferStatus.COMPLETED, result.transfer.status)
        assertEquals(entryId, result.transfer.coreEntryId)
        assertTrue(fakeCore.calls.isEmpty())
    }

    @Test
    fun `처리 중 5초 지남 - 조회해서 안 됐으면 FAILED (core에 보내기 전에 죽은 경우)`() {
        val key = newKey()
        existing(key, TransferStatus.PROCESSING, age = stale)
        fakeCore.lookupRespond = { CoreTransferLookupResult.NotProcessed }

        val result = request(key)

        assertEquals(TransferStatus.FAILED, result.transfer.status)
        assertEquals("CORE_NOT_PROCESSED", result.transfer.failureCode)
        assertTrue(fakeCore.calls.isEmpty())
    }

    // ───────────── 내용이 다름: 충돌 ─────────────

    @Test
    fun `같은 키에 다른 금액 - 충돌, core를 다시 부르지 않는다`() {
        val key = newKey()
        request(key, amount = 10_000)

        assertFailsWith<IdempotencyConflictException> { request(key, amount = 20_000) }
        assertEquals(1, fakeCore.calls.size)
    }

    @Test
    fun `같은 키에 다른 받는 계좌 - 충돌 (금액만 비교하면 놓친다)`() {
        val key = newKey()
        request(key)

        assertFailsWith<IdempotencyConflictException> { request(key, to = UUID.randomUUID()) }
    }

    // ───────────── 동시에 같은 키 ─────────────

    @Test
    fun `같은 키 동시 요청 - 송금 1건, core 호출 1번, 둘 다 같은 송금을 받는다`() {
        val key = newKey()
        // 경합을 운에 맡기지 않는다: 새 송금 INSERT(save) 두 개를 입구에서 붙잡았다가 동시에 놓는다
        // → 둘 다 "기존 송금 없음"을 본 뒤라 둘 다 INSERT → 늦은 쪽이 UNIQUE 위반 (매번 재현)
        val bothAtInsert = CountDownLatch(2)
        val inserts = AtomicInteger()
        val duplicates = AtomicInteger()
        doAnswer { invocation ->
            val transfer = invocation.getArgument<Transfer>(0)
            if (transfer.version == null && inserts.incrementAndGet() <= 2) {   // 새 송금 INSERT만, 처음 두 번만
                bothAtInsert.countDown()
                bothAtInsert.await(5, TimeUnit.SECONDS)
            }
            try {
                invocation.callRealMethod()
            } catch (e: DuplicateIdempotencyKeyException) {
                duplicates.incrementAndGet()
                throw e
            }
        }.whenever(repositorySpy).save(any())

        val pool = Executors.newFixedThreadPool(2)
        val results = try {
            listOf(pool.submit<TransferRequestResult> { request(key) }, pool.submit<TransferRequestResult> { request(key) })
                .map { it.get(10, TimeUnit.SECONDS) }
        } finally {
            pool.shutdown()
        }

        assertEquals(1, duplicates.get(), "경합이 실제로 일어나지 않았다 (UNIQUE 위반이 한 번 나야 한다)")
        assertEquals(1, countByKey(key))
        assertEquals(1, fakeCore.calls.size)
        assertEquals(1, results.map { it.transfer.id }.distinct().size)
        assertEquals(1, results.count { it.replayed }, "한쪽은 새로 만들고, 한쪽은 기존 송금을 받아야 한다")
    }
}
