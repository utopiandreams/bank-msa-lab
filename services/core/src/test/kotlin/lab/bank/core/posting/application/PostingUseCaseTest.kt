package lab.bank.core.posting.application

import lab.bank.core.TestcontainersConfiguration
import lab.bank.core.account.application.AccountUseCase
import lab.bank.core.account.application.UserId
import lab.bank.core.account.domain.exception.AccountNotFoundException
import lab.bank.core.account.domain.exception.InsufficientBalanceException
import lab.bank.core.ledger.domain.exception.IdempotencyConflictException
import lab.bank.core.ledger.infrastructure.JournalEntryRepository
import lab.bank.core.posting.application.exception.SameAccountTransferException
import lab.bank.core.shared.Amount
import lab.bank.core.shared.IdempotencyKey
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mockingDetails
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// 유스케이스 통합 테스트: posting → account + ledger를 진짜 Postgres에서 끝까지 돌린다
//
// @DataJpaTest가 아니라 @SpringBootTest인 이유: 유스케이스 빈(posting, account, ledger)이 다 필요하다
// 테스트에 @Transactional을 걸지 않는 이유: 동시성 테스트는 진짜 커밋이 일어나야 재현된다.
//   대신 롤백이 없으니 테스트끼리 데이터가 섞이지 않게 계좌와 멱등 키를 매번 새로 만든다 (newAccount(), newKey())
//
// 0원 금액·잘못된 멱등 키 테스트가 여기 없는 이유: posting이 Amount·IdempotencyKey를 받으므로 그런 호출은 만들 수조차 없다.
// 그 규칙은 shared의 AmountTest·IdempotencyKeyTest가 검사한다
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class PostingUseCaseTest {

    @Autowired lateinit var postingUseCase: PostingUseCase
    @Autowired lateinit var accountUseCase: AccountUseCase
    @MockitoSpyBean lateinit var journalEntryRepository: JournalEntryRepository
    @Autowired lateinit var transactionTemplate: TransactionTemplate

    private val owner = UserId("u-1")
    private fun newAccount(owner: UserId = this.owner): UUID = accountUseCase.create(owner).id
    private fun newKey() = IdempotencyKey("key-${UUID.randomUUID()}")
    // 테스트는 내부 조회가 필요해서 리포지토리 대신 주인으로 조회한다 (계좌는 전부 owner 것, 영희 계좌만 따로)
    private fun balanceOf(accountId: UUID, owner: UserId = this.owner): Long = accountUseCase.getOwned(accountId, owner).balance

    // 잔액이 있는 계좌 (출금·송금 테스트의 준비물)
    private fun fundedAccount(amount: Long): UUID =
        newAccount().also { postingUseCase.deposit(newKey(), it, Amount(amount)) }

    // 원장에 남은 거래의 분개 줄 수. 거래가 없으면 null
    // 트랜잭션으로 감싸는 이유: 테스트에는 트랜잭션이 없어서, 감싸지 않으면 lines 지연 로딩이 LazyInitializationException으로 실패한다
    private fun ledgerLineCount(key: IdempotencyKey): Int? =
        transactionTemplate.execute { journalEntryRepository.findByIdempotencyKey(key.value)?.lines?.size }

    // ───────────── 입금 ─────────────

    @Test
    fun `입금하면 잔액이 늘고 원장에 2줄이 생긴다`() {
        val accountId = newAccount()
        val key = newKey()

        val result = postingUseCase.deposit(key, accountId, Amount(10_000))

        assertEquals(10_000, balanceOf(accountId))
        assertFalse(result.replayed)
        assertEquals(2, ledgerLineCount(key))   // 차변 현금 / 대변 고객예금
    }

    @Test
    fun `같은 키로 두 번 입금하면 잔액은 한 번만 늘고 같은 거래 ID를 받는다`() {
        val accountId = newAccount()
        val key = newKey()

        val first = postingUseCase.deposit(key, accountId, Amount(100_000))
        val second = postingUseCase.deposit(key, accountId, Amount(100_000))

        assertEquals(100_000, balanceOf(accountId))   // 200_000이면 이중 입금
        assertEquals(first.entryId, second.entryId)
        assertFalse(first.replayed)
        assertTrue(second.replayed)
    }

    @Test
    fun `같은 키에 다른 금액이면 예외가 나고 잔액은 그대로다`() {
        val accountId = newAccount()
        val key = newKey()
        postingUseCase.deposit(key, accountId, Amount(100_000))

        assertFailsWith<IdempotencyConflictException> { postingUseCase.deposit(key, accountId, Amount(200_000)) }

        assertEquals(100_000, balanceOf(accountId))
    }

    @Test
    fun `없는 계좌에 입금하면 예외가 나고 원장에도 남지 않는다`() {
        val key = newKey()

        assertFailsWith<AccountNotFoundException> { postingUseCase.deposit(key, UUID.randomUUID(), Amount(10_000)) }

        // ledger가 먼저 INSERT(saveAndFlush)까지 했는데 account에서 실패했다 → 그 INSERT도 롤백돼야 한다.
        // "원장은 있는데 잔액은 안 바뀐" 상태가 남지 않는다 = 한 트랜잭션이라는 증거
        assertNull(ledgerLineCount(key))
    }

    @Test
    fun `같은 키로 동시에 입금해도 잔액은 한 번만 늘고 모두 같은 거래 ID를 받는다`() {
        val accountId = newAccount()
        val key = newKey()

        // 경합을 운에 맡기지 않고 강제로 만든다:
        // 두 스레드 모두 "기존 거래 없음"을 확인한 뒤에야 INSERT로 넘어가게, 조회 직후에 서로를 기다리게 한다
        val bothChecked = CountDownLatch(2)
        // Spring Data 리포지토리는 인터페이스의 JDK 프록시라 invocation.callRealMethod()가 안 된다 (부를 구현이 없다).
        // @MockitoSpyBean이 이런 빈에 걸어 둔 기본 응답("원래 빈에 위임")을 꺼내서 진짜 조회로 쓴다
        val realCall = mockingDetails(journalEntryRepository).mockCreationSettings.defaultAnswer
        doAnswer { invocation ->
            val result = realCall.answer(invocation)   // 진짜 조회 (둘 다 null을 본다)
            bothChecked.countDown()
            // 타임아웃: 한 스레드만 도착하는 버그가 있어도 테스트가 영원히 멈추지 않게
            // 재시도 때의 3번째 호출은 이미 0이라 바로 통과한다
            bothChecked.await(5, TimeUnit.SECONDS)
            result
        }.whenever(journalEntryRepository).findByIdempotencyKey(any())

        // when: 두 스레드가 같은 키로 입금 (일을 다 맡긴 다음에 결과를 모은다. 하나씩 맡기고 기다리면 동시가 아니다)
        val executor = Executors.newFixedThreadPool(2)
        val results = try {
            List(2) { executor.submit<PostingResult> { postingUseCase.deposit(key, accountId, Amount(100_000)) } }
                .map { it.get(10, TimeUnit.SECONDS) }   // 스레드 안의 예외는 여기서 ExecutionException으로 다시 던져진다
        } finally {
            executor.shutdown()
        }

        // then: 잔액은 한 번만 늘고, 모두 같은 거래 ID, 실제로 처리한 쪽은 정확히 하나
        assertEquals(100_000, balanceOf(accountId))
        assertEquals(1, results.map { it.entryId }.distinct().size)
        assertEquals(1, results.count { !it.replayed })
        // 경합이 실제로 일어났다는 증거: 조회 2번 + 늦은 쪽의 재시도 조회 1번
        // (경합이 없었다면 2번에서 끝났다. 이걸 확인하지 않으면 "운 좋게 통과"와 구분이 안 된다)
        verify(journalEntryRepository, times(3)).findByIdempotencyKey(key.value)
    }

    // ───────────── 출금 ─────────────

    @Test
    fun `출금하면 잔액이 줄고 원장에 2줄이 생긴다`() {
        val accountId = fundedAccount(10_000)
        val key = newKey()

        val result = postingUseCase.withdraw(key, accountId, Amount(3_000))

        assertEquals(7_000, balanceOf(accountId))
        assertFalse(result.replayed)
        assertEquals(2, ledgerLineCount(key))   // 차변 고객예금 / 대변 현금
    }

    @Test
    fun `잔액보다 많이 출금하면 예외가 나고 잔액과 원장 모두 그대로다`() {
        val accountId = fundedAccount(10_000)
        val key = newKey()

        assertFailsWith<InsufficientBalanceException> { postingUseCase.withdraw(key, accountId, Amount(10_001)) }

        assertEquals(10_000, balanceOf(accountId))
        // 원장 INSERT는 잔액 검사보다 먼저 일어났다. 잔액 부족으로 실패하면 원장 기록도 함께 롤백돼야 한다
        assertNull(ledgerLineCount(key))
    }

    @Test
    fun `같은 키로 두 번 출금하면 잔액은 한 번만 줄고 같은 거래 ID를 받는다`() {
        val accountId = fundedAccount(10_000)
        val key = newKey()

        val first = postingUseCase.withdraw(key, accountId, Amount(3_000))
        val second = postingUseCase.withdraw(key, accountId, Amount(3_000))

        assertEquals(7_000, balanceOf(accountId))   // 4_000이면 이중 출금 사고
        assertEquals(first.entryId, second.entryId)
        assertFalse(first.replayed)
        assertTrue(second.replayed)
    }

    // ───────────── 당행 송금 ─────────────

    @Test
    fun `송금하면 보낸 쪽은 줄고 받는 쪽은 늘고 합계는 그대로다`() {
        val from = fundedAccount(10_000)
        val to = fundedAccount(5_000)
        val key = newKey()

        val result = postingUseCase.transfer(owner, key, from, to, Amount(3_000))

        assertEquals(7_000, balanceOf(from))
        assertEquals(8_000, balanceOf(to))
        // 당행 송금은 돈이 은행 밖으로 나가지 않는다: 두 계좌의 합이 그대로여야 한다 (대사의 가장 기본 검사)
        assertEquals(15_000, balanceOf(from) + balanceOf(to))
        assertFalse(result.replayed)
        // 송금 1건 = 거래 1건, 분개 2줄 (차변 고객예금 A / 대변 고객예금 B). 출금·입금 거래 2건으로 쪼개지 않는다
        assertEquals(2, ledgerLineCount(key))
    }

    @Test
    fun `받는 계좌가 없으면 예외가 나고 보낸 쪽 출금도 롤백된다`() {
        val from = fundedAccount(10_000)
        val key = newKey()

        // 순서: 원장 기록 → 보낸 쪽 출금(성공) → 받는 쪽 조회(실패)
        assertFailsWith<AccountNotFoundException> { postingUseCase.transfer(owner, key, from, UUID.randomUUID(), Amount(3_000)) }

        // 이미 성공했던 출금까지 되돌아가야 한다. 7_000이면 "돈이 사라진" 사고
        assertEquals(10_000, balanceOf(from))
        assertNull(ledgerLineCount(key))
    }

    @Test
    fun `보내는 쪽 잔액이 부족하면 예외가 나고 두 계좌 모두 그대로다`() {
        val from = fundedAccount(1_000)
        val to = fundedAccount(5_000)
        val key = newKey()

        assertFailsWith<InsufficientBalanceException> { postingUseCase.transfer(owner, key, from, to, Amount(3_000)) }

        assertEquals(1_000, balanceOf(from))
        assertEquals(5_000, balanceOf(to))
        assertNull(ledgerLineCount(key))
    }

    @Test
    fun `같은 키로 두 번 송금하면 한 번만 반영되고 같은 거래 ID를 받는다`() {
        val from = fundedAccount(10_000)
        val to = newAccount()
        val key = newKey()

        val first = postingUseCase.transfer(owner, key, from, to, Amount(3_000))
        val second = postingUseCase.transfer(owner, key, from, to, Amount(3_000))

        assertEquals(7_000, balanceOf(from))
        assertEquals(3_000, balanceOf(to))
        assertEquals(first.entryId, second.entryId)
        assertTrue(second.replayed)
    }

    @Test
    fun `같은 키로 받는 계좌만 바꿔 송금하면 예외가 나고 잔액은 그대로다`() {
        val from = fundedAccount(10_000)
        val to = newAccount()
        val other = newAccount()
        val key = newKey()
        postingUseCase.transfer(owner, key, from, to, Amount(3_000))

        // 금액이 같아도 받는 사람이 다르면 다른 요청이다 (분개 줄의 계좌 ID까지 비교하는 isSameRequestAs가 잡는다)
        assertFailsWith<IdempotencyConflictException> { postingUseCase.transfer(owner, key, from, other, Amount(3_000)) }

        assertEquals(7_000, balanceOf(from))
        assertEquals(3_000, balanceOf(to))
        assertEquals(0, balanceOf(other))
    }

    // ───────────── 소유자 검사 (G-4, BOLA) ─────────────

    // 재현 (2026-11-14 클러스터): 영희 토큰으로 철수 계좌에서 3만 원이 빠져나갔다
    @Test
    fun `남의 계좌에서 송금하면 없는 계좌와 같은 예외가 나고 잔액·원장 모두 그대로다`() {
        val chulsoos = fundedAccount(10_000)
        val younghee = UserId("younghee")
        val youngheesAccount = newAccount(younghee)
        val key = newKey()

        assertFailsWith<AccountNotFoundException> {
            postingUseCase.transfer(younghee, key, chulsoos, youngheesAccount, Amount(3_000))
        }

        assertEquals(10_000, balanceOf(chulsoos))
        assertEquals(0, balanceOf(youngheesAccount, younghee))
        assertNull(ledgerLineCount(key))   // 원장 기록도 함께 롤백
    }

    @Test
    fun `받는 계좌는 남의 것이어도 된다 (송금은 원래 남에게 보내는 것)`() {
        val from = fundedAccount(10_000)
        val youngheesAccount = newAccount(UserId("younghee"))

        postingUseCase.transfer(owner, newKey(), from, youngheesAccount, Amount(3_000))

        assertEquals(7_000, balanceOf(from))
    }

    // 소유자 검사를 원장 기록(멱등 판정) 앞에 두면 이 테스트가 깨진다:
    // 이미 처리된 거래의 재전송이 거절되면 transfer는 FAILED로 확정 → 돈은 빠졌는데 고객에겐 실패 (pitfalls 17)
    @Test
    fun `이미 처리된 거래의 재요청은 소유자를 다시 판정하지 않고 원래 결과를 돌려준다`() {
        val from = fundedAccount(10_000)
        val to = newAccount()
        val key = newKey()
        val first = postingUseCase.transfer(owner, key, from, to, Amount(3_000))

        // 예: V3 이전 송금의 UNKNOWN 해소. transfer가 요청자를 'legacy'로 실어 재전송한다
        val retried = postingUseCase.transfer(UserId("legacy"), key, from, to, Amount(3_000))

        assertTrue(retried.replayed)
        assertEquals(first.entryId, retried.entryId)
        assertEquals(7_000, balanceOf(from))
    }

    @Test
    fun `같은 계좌로 송금하면 입구에서 거절되고 원장에 남지 않는다`() {
        val accountId = fundedAccount(10_000)
        val key = newKey()

        assertFailsWith<SameAccountTransferException> { postingUseCase.transfer(owner, key, accountId, accountId, Amount(3_000)) }

        assertEquals(10_000, balanceOf(accountId))
        assertNull(ledgerLineCount(key))
    }
}
