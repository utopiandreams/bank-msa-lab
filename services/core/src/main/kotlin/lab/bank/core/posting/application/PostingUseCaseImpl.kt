package lab.bank.core.posting.application

import lab.bank.core.account.application.AccountUseCase
import lab.bank.core.account.application.UserId
import lab.bank.core.ledger.application.LedgerUseCase
import lab.bank.core.posting.application.exception.SameAccountTransferException
import lab.bank.core.shared.Amount
import lab.bank.core.shared.IdempotencyKey
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.beans.factory.annotation.Value
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID
import kotlin.random.Random

/**
 * 트랜잭션 경계를 @Transactional이 아니라 TransactionTemplate으로 직접 잡는다.
 * 이유: "트랜잭션 밖에서 실패를 잡고, 새 트랜잭션으로 다시 시도"해야 하는데, 같은 클래스 안의 @Transactional 메서드를
 * this로 부르면 프록시를 거치지 않아 트랜잭션이 안 걸린다 (self-invocation 함정). 템플릿은 경계가 코드에 그대로 보인다.
 */
@Service
class PostingUseCaseImpl(
    private val transactionTemplate: TransactionTemplate,
    private val accountUseCase: AccountUseCase,
    private val ledgerUseCase: LedgerUseCase,
    // 낙관적 락 충돌 시 트랜잭션을 몇 번까지 시도하나 (첫 시도 포함, LockProperties와 같은 키). 모듈 경계상 account의 설정 클래스 대신 값만 받는다
    @Value("\${core.lock.optimistic-max-attempts:3}") private val optimisticMaxAttempts: Int,
    meterRegistry: MeterRegistry,
) : PostingUseCase {

    // 재시도 횟수 지표: k6 실험에서 "충돌이 얼마나 났나"를 Grafana로 본다 (core_posting_optimistic_retries_total)
    private val optimisticRetries: Counter = Counter.builder("core.posting.optimistic.retries")
        .description("낙관적 락 충돌로 트랜잭션을 다시 시도한 횟수")
        .register(meterRegistry)

    // 금액·멱등 키 검증은 여기 없다: Amount·IdempotencyKey 타입을 받았다는 것 자체가 이미 검증됐다는 뜻이다
    override fun deposit(idempotencyKey: IdempotencyKey, accountId: UUID, amount: Amount): PostingResult =
        retryOnOptimisticLock { retryOnceOnDuplicateKey { depositInTransaction(idempotencyKey, accountId, amount) } }

    override fun withdraw(idempotencyKey: IdempotencyKey, accountId: UUID, amount: Amount): PostingResult =
        retryOnOptimisticLock { retryOnceOnDuplicateKey { withdrawInTransaction(idempotencyKey, accountId, amount) } }

    override fun transfer(requester: UserId, idempotencyKey: IdempotencyKey, fromAccountId: UUID, toAccountId: UUID, amount: Amount): PostingResult {
        // 값 하나로는 알 수 없고 필드 두 개를 함께 봐야 하는 규칙이라 값 객체가 아니라 여기서 검사한다.
        // 트랜잭션·재시도보다 먼저: 잘못된 요청은 DB 커넥션을 잡기 전에 돌려보낸다
        if (fromAccountId == toAccountId) throw SameAccountTransferException(fromAccountId)
        return retryOnOptimisticLock { retryOnceOnDuplicateKey { transferInTransaction(requester, idempotencyKey, fromAccountId, toAccountId, amount) } }
    }

    override fun interbankWithdraw(requester: UserId, idempotencyKey: IdempotencyKey, accountId: UUID, amount: Amount): PostingResult =
        retryOnOptimisticLock { retryOnceOnDuplicateKey {
            // 소유자 검사는 당행 송금과 같이 멱등 판정 뒤, 새로 처리할 때만 (pitfalls 17)
            transactionTemplate.execute {
                val recorded = ledgerUseCase.recordInterbankWithdraw(idempotencyKey, accountId, amount)
                if (!recorded.replayed) {
                    accountUseCase.withdrawOwned(requester, accountId, amount, recorded.entryId)
                }
                PostingResult(recorded.entryId, recorded.replayed)
            }
        } }

    override fun cancelInterbankWithdraw(originalKey: IdempotencyKey): CancelResult =
        retryOnOptimisticLock { retryOnceOnDuplicateKey {
            // 취소 전표와 잔액 복구가 한 트랜잭션. 이미 취소됐으면(replayed) 잔액을 다시 바꾸지 않는다
            transactionTemplate.execute {
                val cancel = ledgerUseCase.recordInterbankWithdrawCancel(originalKey)
                if (!cancel.replayed) {
                    accountUseCase.deposit(cancel.accountId, cancel.amount, cancel.entryId)
                }
                CancelResult(cancel.entryId, cancel.replayed, cancel.idempotencyKey)
            }
        } }

    // 원장 기록과 잔액 변경이 한 트랜잭션: 둘 다 되거나 둘 다 안 된다
    private fun depositInTransaction(idempotencyKey: IdempotencyKey, accountId: UUID, amount: Amount): PostingResult =
        transactionTemplate.execute {
            val recorded = ledgerUseCase.recordDeposit(idempotencyKey, accountId, amount)
            // 이미 처리된 요청이면 잔액을 다시 바꾸지 않는다 (멱등의 핵심)
            if (!recorded.replayed) {
                accountUseCase.deposit(accountId, amount, recorded.entryId)
            }
            PostingResult(recorded.entryId, recorded.replayed)
        }

    private fun withdrawInTransaction(idempotencyKey: IdempotencyKey, accountId: UUID, amount: Amount): PostingResult =
        transactionTemplate.execute {
            val recordResult = ledgerUseCase.recordWithdraw(idempotencyKey, accountId, amount)
            if (!recordResult.replayed) {
                accountUseCase.withdraw(accountId, amount, recordResult.entryId)
            }
            PostingResult(recordResult.entryId, recordResult.replayed)
        }

    /**
     * 소유자 검사는 원장 기록(멱등 판정) **뒤**, 새로 처리할 때만 한다 (accountUseCase.transfer 안).
     * 재요청(replayed)은 처음 처리할 때 이미 허가된 거래라 다시 판정하지 않고 원래 결과를 돌려준다.
     * 검사를 앞에 두면: 이미 처리된 거래의 재전송(transfer의 UNKNOWN 해소)이 거절되고 → transfer는 FAILED로 확정
     * → 돈은 빠졌는데 고객에겐 실패로 보인다 (docs/learning/pitfalls.md 17).
     * 소유자가 아니면 예외 → 원장 기록도 함께 롤백된다 (한 트랜잭션).
     */
    private fun transferInTransaction(requester: UserId, idempotencyKey: IdempotencyKey, fromAccountId: UUID, toAccountId: UUID, amount: Amount): PostingResult =
        transactionTemplate.execute {
            val recordResult = ledgerUseCase.recordTransfer(idempotencyKey, fromAccountId, toAccountId, amount)
            if (!recordResult.replayed) {
                accountUseCase.transfer(requester, fromAccountId, toAccountId, amount, recordResult.entryId)
            }
            PostingResult(recordResult.entryId, recordResult.replayed)
        }


    override fun find(idempotencyKey: IdempotencyKey): PostingView? =
        ledgerUseCase.findEntry(idempotencyKey)?.let { PostingView(it.entryId, it.type, it.createdAt) }

    /**
     * 같은 키 요청 두 개가 동시에 들어오면 둘 다 "기존 거래 없음"을 보고 진행하고, 늦은 쪽이 UNIQUE 위반으로 실패한다.
     * 실패한 트랜잭션 안에서는 수습할 수 없다 (PostgreSQL: current transaction is aborted / Spring: rollback-only).
     * 그래서 트랜잭션 밖에서 잡고, 새 트랜잭션으로 한 번 더 시도한다. 이번엔 먼저 커밋된 거래를 보고
     * 같은 내용이면 replayed, 다르면 IdempotencyConflictException이 된다.
     *
     * 한계: CHECK 제약 위반 같은 다른 무결성 오류도 한 번 재시도된다 (두 번째도 같은 이유로 실패하고 그대로 올라간다).
     */
    /**
     * 낙관적 락 충돌(커밋 때 버전 불일치)이면 **트랜잭션 전체를 새로** 다시 한다 (7주 차).
     * 충돌한 트랜잭션은 이미 롤백됐고 그 안의 엔티티는 옛 값이라, 같은 트랜잭션 안에서는 고칠 수 없다 (retryOnceOnDuplicateKey와 같은 이유).
     * 원장 기록부터 다시 하므로 멱등 판정도 다시 한다.
     *
     * 지터(무작위 대기): 같이 충돌한 요청들이 똑같이 바로 다시 오면 또 같은 순간에 부딪힌다. 시도할수록 조금 더 기다린다.
     * 다 써도 충돌이면 예외 그대로 → 500 (transfer는 결과 불명으로 분류. 확실히 안 된 것이라 503 + 재시도 안내가 더 정확하다, 후속)
     * OPTIMISTIC 외의 전략은 이 예외를 내지 않아서 감싸 둬도 비용이 없다.
     */
    private fun <T> retryOnOptimisticLock(block: () -> T): T {
        var attempt = 1
        while (true) {
            try {
                return block()
            } catch (e: OptimisticLockingFailureException) {
                if (attempt >= optimisticMaxAttempts) throw e
                optimisticRetries.increment()
                Thread.sleep(Random.nextLong(5, 20) * attempt)
                attempt++
            }
        }
    }

    private fun <T> retryOnceOnDuplicateKey(block: () -> T): T =
        try {
            block()
        } catch (e: DataIntegrityViolationException) {
            block()
        }
}
