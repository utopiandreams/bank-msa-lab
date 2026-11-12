package lab.bank.core.posting.application

import lab.bank.core.account.application.AccountUseCase
import lab.bank.core.ledger.application.LedgerUseCase
import lab.bank.core.posting.application.exception.InvalidIdempotencyKeyException
import lab.bank.core.posting.application.exception.InvalidPostingAmountException
import lab.bank.core.posting.application.exception.SameAccountTransferException
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

    // 입구 검증은 트랜잭션·재시도보다 먼저: 잘못된 요청은 DB 커넥션을 잡기 전에 돌려보낸다
    override fun deposit(idempotencyKey: String, accountId: UUID, amount: Long): PostingResult {
        validateIdempotencyKey(idempotencyKey)
        validateAmount(amount)
        return retryOnceOnDuplicateKey { depositInTransaction(idempotencyKey, accountId, amount) }
    }

    override fun withdraw(idempotencyKey: String, accountId: UUID, amount: Long): PostingResult {
        validateIdempotencyKey(idempotencyKey)
        validateAmount(amount)
        return retryOnceOnDuplicateKey { withdrawInTransaction(idempotencyKey, accountId, amount) }
    }

    override fun transfer(idempotencyKey: String, fromAccountId: UUID, toAccountId: UUID, amount: Long): PostingResult {
        validateIdempotencyKey(idempotencyKey)
        validateAmount(amount)
        if (fromAccountId == toAccountId) throw SameAccountTransferException(fromAccountId)
        return retryOnceOnDuplicateKey { transferInTransaction(idempotencyKey, fromAccountId, toAccountId, amount) }
    }

    private fun validateAmount(amount: Long) {
        if (amount <= 0) throw InvalidPostingAmountException(amount)
    }

    // 길이 제한은 DB 컬럼(varchar(64))과 맞춘다. 안 막으면 INSERT에서 DataIntegrityViolationException이 나서
    // 아래 중복 키 재시도에 잘못 잡히고(한 번 더 시도), 결국 500으로 나간다. 고객이 고칠 수 있는 문제인데 서버 장애처럼 보인다
    private fun validateIdempotencyKey(idempotencyKey: String) {
        if (idempotencyKey.isBlank()) throw InvalidIdempotencyKeyException("비어 있음")
        if (idempotencyKey.length > MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw InvalidIdempotencyKeyException("${MAX_IDEMPOTENCY_KEY_LENGTH}자 초과 (${idempotencyKey.length}자)")
        }
    }

    companion object {
        private const val MAX_IDEMPOTENCY_KEY_LENGTH = 64
    }

    // 원장 기록과 잔액 변경이 한 트랜잭션: 둘 다 되거나 둘 다 안 된다
    private fun depositInTransaction(idempotencyKey: String, accountId: UUID, amount: Long): PostingResult =
        transactionTemplate.execute {
            val recorded = ledgerUseCase.recordDeposit(idempotencyKey, accountId, amount)
            // 이미 처리된 요청이면 잔액을 다시 바꾸지 않는다 (멱등의 핵심)
            if (!recorded.replayed) {
                accountUseCase.deposit(accountId, amount, recorded.entryId)
            }
            PostingResult(recorded.entryId, recorded.replayed)
        }

    private fun withdrawInTransaction(idempotencyKey: String, accountId: UUID, amount: Long): PostingResult =
        transactionTemplate.execute {
            val recordResult = ledgerUseCase.recordWithdraw(idempotencyKey, accountId, amount)
            if (!recordResult.replayed) {
                accountUseCase.withdraw(accountId, amount, recordResult.entryId)
            }
            PostingResult(recordResult.entryId, recordResult.replayed)
        }

    private fun transferInTransaction(idempotencyKey: String, fromAccountId: UUID, toAccountId: UUID, amount: Long): PostingResult =
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
