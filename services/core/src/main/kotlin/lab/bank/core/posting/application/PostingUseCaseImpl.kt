package lab.bank.core.posting.application

import lab.bank.core.account.application.AccountUseCase
import lab.bank.core.ledger.application.LedgerUseCase
import lab.bank.core.posting.application.exception.SameAccountTransferException
import lab.bank.core.shared.Amount
import lab.bank.core.shared.IdempotencyKey
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID

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
) : PostingUseCase {

    // 금액·멱등 키 검증은 여기 없다: Amount·IdempotencyKey 타입을 받았다는 것 자체가 이미 검증됐다는 뜻이다
    override fun deposit(idempotencyKey: IdempotencyKey, accountId: UUID, amount: Amount): PostingResult =
        retryOnceOnDuplicateKey { depositInTransaction(idempotencyKey, accountId, amount) }

    override fun withdraw(idempotencyKey: IdempotencyKey, accountId: UUID, amount: Amount): PostingResult =
        retryOnceOnDuplicateKey { withdrawInTransaction(idempotencyKey, accountId, amount) }

    override fun transfer(idempotencyKey: IdempotencyKey, fromAccountId: UUID, toAccountId: UUID, amount: Amount): PostingResult {
        // 값 하나로는 알 수 없고 필드 두 개를 함께 봐야 하는 규칙이라 값 객체가 아니라 여기서 검사한다.
        // 트랜잭션·재시도보다 먼저: 잘못된 요청은 DB 커넥션을 잡기 전에 돌려보낸다
        if (fromAccountId == toAccountId) throw SameAccountTransferException(fromAccountId)
        return retryOnceOnDuplicateKey { transferInTransaction(idempotencyKey, fromAccountId, toAccountId, amount) }
    }

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

    private fun transferInTransaction(idempotencyKey: IdempotencyKey, fromAccountId: UUID, toAccountId: UUID, amount: Amount): PostingResult =
        transactionTemplate.execute {
            val recordResult = ledgerUseCase.recordTransfer(idempotencyKey, fromAccountId, toAccountId, amount)
            if (!recordResult.replayed) {
                accountUseCase.transfer(fromAccountId, toAccountId, amount, recordResult.entryId)
            }
            PostingResult(recordResult.entryId, recordResult.replayed)
        }


    /**
     * 같은 키 요청 두 개가 동시에 들어오면 둘 다 "기존 거래 없음"을 보고 진행하고, 늦은 쪽이 UNIQUE 위반으로 실패한다.
     * 실패한 트랜잭션 안에서는 수습할 수 없다 (PostgreSQL: current transaction is aborted / Spring: rollback-only).
     * 그래서 트랜잭션 밖에서 잡고, 새 트랜잭션으로 한 번 더 시도한다. 이번엔 먼저 커밋된 거래를 보고
     * 같은 내용이면 replayed, 다르면 IdempotencyConflictException이 된다.
     *
     * 한계: CHECK 제약 위반 같은 다른 무결성 오류도 한 번 재시도된다 (두 번째도 같은 이유로 실패하고 그대로 올라간다).
     */
    private fun <T> retryOnceOnDuplicateKey(block: () -> T): T =
        try {
            block()
        } catch (e: DataIntegrityViolationException) {
            block()
        }
}
