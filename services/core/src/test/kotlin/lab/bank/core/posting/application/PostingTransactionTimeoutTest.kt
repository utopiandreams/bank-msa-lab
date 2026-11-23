package lab.bank.core.posting.application

import lab.bank.core.TestcontainersConfiguration
import lab.bank.core.account.application.AccountUseCase
import lab.bank.core.account.application.UserId
import lab.bank.core.ledger.infrastructure.JournalEntryRepository
import lab.bank.core.shared.Amount
import lab.bank.core.shared.IdempotencyKey

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 8주 차 (ADR-0005 결정 3): core 거래 트랜잭션은 **시작하고 timeout이 지나면 커밋하지 않는다.**
 *
 * 왜: transfer 해소 일꾼은 보낸 지 5초 지난 송금을 조회해 "전표 없음"이면 FAILED로 확정한다.
 * core가 그때 아직 처리 중이었다가 뒤늦게 커밋하면 돈은 나갔는데 FAILED (늦은 커밋).
 * 트랜잭션에 상한(운영 3초)을 걸면 5초 뒤의 "없음"을 믿을 수 있다.
 *
 * 재현 방법: 다른 트랜잭션이 보내는 계좌 행을 FOR UPDATE로 붙잡는다 → 송금의 조건부 UPDATE(ATOMIC_UPDATE)가 행 락을 기다린다.
 * 테스트를 빨리 돌리려고 timeout을 1초로 둔다 (Spring 트랜잭션 타임아웃은 초 단위).
 *
 * 한계 (ADR-0005): 시계는 트랜잭션 시작부터. 그 앞(톰캣 대기열, 커넥션 풀, Redis 락 대기)과 재시도 루프(OPTIMISTIC 전략)는
 * 시계를 다시 시작한다 → 남는 늦은 커밋은 대사가 잡는다.
 */
@SpringBootTest(properties = ["core.posting.transaction-timeout=1s"])
@Import(TestcontainersConfiguration::class)
class PostingTransactionTimeoutTest {

    @Autowired lateinit var postingUseCase: PostingUseCase
    @Autowired lateinit var accountUseCase: AccountUseCase
    @Autowired lateinit var journalEntryRepository: JournalEntryRepository
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var transactionManager: PlatformTransactionManager

    private val owner = UserId("chulsoo")
    private val plainTx by lazy { TransactionTemplate(transactionManager) }   // 타임아웃 없는 테스트용 트랜잭션

    private fun newKey() = IdempotencyKey("timeout-${UUID.randomUUID()}")

    private fun newAccount(): UUID = accountUseCase.create(owner).id

    private fun fundedAccount(amount: Long): UUID =
        newAccount().also { postingUseCase.deposit(newKey(), it, Amount(amount)) }

    private fun balanceOf(id: UUID) = accountUseCase.getOwned(id, owner).balance

    /** 다른 트랜잭션이 계좌 행을 holdMillis 동안 붙잡는다. 잡은 뒤에 돌아온다 */
    private fun holdRowLock(accountId: UUID, holdMillis: Long): () -> Unit {
        val locked = CountDownLatch(1)
        val pool = Executors.newSingleThreadExecutor()
        val done = pool.submit {
            plainTx.executeWithoutResult {
                jdbc.queryForList("SELECT id FROM account.accounts WHERE id = ? FOR UPDATE", accountId)
                locked.countDown()
                Thread.sleep(holdMillis)
            }
        }
        assertTrue(locked.await(5, TimeUnit.SECONDS))
        return { done.get(10, TimeUnit.SECONDS); pool.shutdown() }
    }

    @Test
    fun `행 락을 timeout보다 오래 기다리면 - 포기하고 롤백, 락이 풀린 뒤에도 전표·잔액 변화 없음 (늦은 커밋 없음)`() {
        val from = fundedAccount(10_000)
        val to = newAccount()
        val key = newKey()
        val awaitHolder = holdRowLock(from, holdMillis = 3_000)

        val startedAt = System.nanoTime()
        val error = runCatching { postingUseCase.transfer(owner, key, from, to, Amount(1_000)) }.exceptionOrNull()
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

        awaitHolder()   // 락이 풀릴 때까지 기다린다: 타임아웃이 없으면 송금이 이때 커밋된다
        assertTrue(error != null, "송금이 실패해야 한다")
        assertTrue(elapsedMs < 2_500, "락이 풀리기(3초) 전에 포기해야 한다: ${elapsedMs}ms, ${error.javaClass.name}")
        assertNull(journalEntryRepository.findByIdempotencyKey(key.value), "전표가 없어야 한다 = 조회하면 NOT_PROCESSED가 진실")
        assertEquals(10_000, balanceOf(from))
        assertEquals(0, balanceOf(to))
    }

    @Test
    fun `timeout 안에 락이 풀리면 - 기다렸다가 정상 처리 (타임아웃이 평범한 대기까지 죽이지 않는다)`() {
        val from = fundedAccount(10_000)
        val to = newAccount()
        val awaitHolder = holdRowLock(from, holdMillis = 300)

        postingUseCase.transfer(owner, newKey(), from, to, Amount(1_000))

        awaitHolder()
        assertEquals(9_000, balanceOf(from))
        assertEquals(1_000, balanceOf(to))
    }
}
