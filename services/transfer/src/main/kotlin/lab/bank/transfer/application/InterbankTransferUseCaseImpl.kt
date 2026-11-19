package lab.bank.transfer.application

import lab.bank.transfer.application.port.out.CoreInterbankPort
import lab.bank.transfer.application.port.out.CoreTransferResult
import lab.bank.transfer.application.port.out.DuplicateIdempotencyKeyException
import lab.bank.transfer.application.port.out.ExternalBankPort
import lab.bank.transfer.application.port.out.ExternalTransferCommand
import lab.bank.transfer.application.port.out.ExternalTransferResult
import lab.bank.transfer.application.port.out.InterbankTransferRepository
import lab.bank.transfer.application.port.out.InterbankWithdrawCommand
import lab.bank.transfer.application.port.out.TransferEventPublisher
import lab.bank.transfer.domain.Amount
import lab.bank.transfer.domain.BankCode
import lab.bank.transfer.domain.IdempotencyConflictException
import lab.bank.transfer.domain.IdempotencyKey
import lab.bank.transfer.domain.InterbankTransfer
import lab.bank.transfer.domain.InterbankTransferStatus.EXTERNAL_BANK_REJECTED
import lab.bank.transfer.domain.InterbankTransferStatus.WITHDRAWN
import lab.bank.transfer.domain.InvalidBankCodeException
import lab.bank.transfer.domain.UserId
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID

/**
 * 타행 송금 사가의 지휘자 (ADR-0004, 오케스트레이션). 당행 TransferUseCaseImpl과 분리한 이유: 의존(포트)과 흐름이 다르다.
 *
 * 단계마다 같은 모양: **의도 상태 커밋 → 트랜잭션 밖에서 원격 호출 → 결과 커밋** (pitfalls 8, 27)
 *   1. 출금       WITHDRAW_REQUESTED      → core 타행 출금  → WITHDRAWN / FAILED / (모름: 그대로)
 *   2. 외부 송금  EXTERNAL_BANK_REQUESTED → bank-mock 송금  → COMPLETED / EXTERNAL_BANK_REJECTED / (모름: 그대로, 보상 금지)
 *   3. 보상       EXTERNAL_BANK_REJECTED  → core 출금 취소  → CANCELLED / (모름: 그대로)
 * 다음 단계로 갈지는 직전 단계가 남긴 **상태**로 판단한다. 결과 불명으로 멈춘 송금은 8주 차 해소 스케줄러가 이어 간다.
 *
 * 저장 포트는 저장된 새 객체(올라간 version)를 돌려준다 → 저장할 때마다 반환값으로 갈아탄다 (낡은 객체로 다시 저장하면 낙관적 락 예외).
 */
@Service
class InterbankTransferUseCaseImpl(
    private val transactionTemplate: TransactionTemplate,
    private val repository: InterbankTransferRepository,
    private val coreInterbankPort: CoreInterbankPort,
    private val externalBankPort: ExternalBankPort,
    private val transferEventPublisher: TransferEventPublisher,
) : InterbankTransferUseCase {

    override fun transfer(
        requesterId: UserId,
        idempotencyKey: IdempotencyKey,
        fromAccountId: UUID,
        toBankCode: BankCode,
        toAccountNumber: String,
        amount: Amount,
    ): InterbankTransferRequestResult {
        validate(toBankCode)
        val request = Request(fromAccountId, toBankCode, toAccountNumber, amount)

        repository.findByIdempotencyKey(requesterId, idempotencyKey)?.let { return replay(it, request) }

        val started = try {
            start(InterbankTransfer.request(requesterId, idempotencyKey, fromAccountId, toBankCode, toAccountNumber, amount))
        } catch (e: DuplicateIdempotencyKeyException) {
            // 같은 키 동시 요청에서 늦은 쪽: 먼저 커밋한 송금을 재요청으로 돌려준다 (당행과 같은 처리)
            // 타행으로는 안 보인다 = 이 키는 당행 송금이 쓰고 있다 (소거법, TransferUseCaseImpl과 같은 판단)
            val winner = repository.findByIdempotencyKey(requesterId, idempotencyKey)
                ?: throw IdempotencyConflictException(idempotencyKey, "당행 송금에 이미 쓰인 멱등 키입니다")
            return replay(winner, request)
        }

        return InterbankTransferRequestResult(runSaga(started), replayed = false)
    }

    // ---- 사가 ----

    private fun runSaga(started: InterbankTransfer): InterbankTransfer {
        val afterWithdraw = withdraw(started)
        if (afterWithdraw.status != WITHDRAWN) return afterWithdraw              // 거절(FAILED) 또는 결과 불명

        val afterSend = sendToExternalBank(afterWithdraw)
        if (afterSend.status != EXTERNAL_BANK_REJECTED) return afterSend         // 성공(COMPLETED) 또는 결과 불명

        return compensate(afterSend)
    }

    // 1단계 의도(WITHDRAW_REQUESTED)는 start에서 송금 생성과 함께 커밋됐다
    private fun withdraw(transfer: InterbankTransfer): InterbankTransfer {
        when (val result = coreInterbankPort.withdraw(InterbankWithdrawCommand.fromDomain(transfer))) {
            is CoreTransferResult.Completed -> transfer.withdrawn(result.coreEntryId)
            is CoreTransferResult.Rejected -> transfer.withdrawRejected(result.code)
            is CoreTransferResult.Unknown -> transfer.withdrawRequested()   // 그대로 (마지막 시도 시각만 갱신), 같은 송금 ID로 재시도 대상
        }
        return save(transfer)
    }

    private fun sendToExternalBank(withdrawn: InterbankTransfer): InterbankTransfer {
        // 호출 **전에** 의도를 커밋한다: 그래야 WITHDRAWN으로 남은 송금은 "외부엔 확실히 안 나감"이 보장된다 (pitfalls 27)
        val requested = save(withdrawn.apply { externalBankRequested() })

        when (val result = externalBankPort.send(ExternalTransferCommand.fromDomain(requested))) {
            is ExternalTransferResult.Accepted -> requested.completed()
            is ExternalTransferResult.Rejected -> requested.externalBankRejected(result.reason)
            is ExternalTransferResult.Unknown -> requested.externalBankRequested()   // 그대로. 보냈을 수도 있다 → 보상 금지, 조회 대상
        }
        return save(requested)
    }

    // 보상의 의도 상태 = EXTERNAL_BANK_REJECTED (외부 거절 결과를 커밋한 상태). 취소 키가 결정적이라 몇 번을 다시 해도 한 번만 취소된다
    private fun compensate(rejected: InterbankTransfer): InterbankTransfer =
        when (val result = coreInterbankPort.cancelWithdraw(rejected.id)) {
            is CoreTransferResult.Completed -> save(rejected.apply { cancelled(result.coreEntryId) })
            // 취소에는 Rejected가 없다 (포트 주석): 보상은 실패로 끝날 수 없다 → 그대로 두고 재시도 대상 (8주 차)
            is CoreTransferResult.Rejected, is CoreTransferResult.Unknown -> rejected
        }

    // ---- 멱등 ----

    // 같은 키 재요청: 내용이 다르면 409, 같으면 지금 상태 그대로 (진행 중이어도 200 + 현재 상태, 원격 호출 없음)
    private fun replay(existing: InterbankTransfer, request: Request): InterbankTransferRequestResult {
        if (!request.matches(existing)) throw IdempotencyConflictException(existing.idempotencyKey)
        return InterbankTransferRequestResult(existing, replayed = true)
    }

    // 재요청 비교용으로 요청 내용을 묶는다 (같은 요청 판정이 두 곳에서 쓰여서)
    private data class Request(val fromAccountId: UUID, val toBankCode: BankCode, val toAccountNumber: String, val amount: Amount) {
        fun matches(existing: InterbankTransfer) = existing.isSameRequestAs(fromAccountId, toBankCode, toAccountNumber, amount)
    }

    // ---- 입구 검증 · 저장 ----

    // 형식은 BankCode 타입이 이미 검증했다. 도메인 init에도 같은 규칙이 있지만 그건 버그 방어(500). 고객 입력은 여기서 400으로
    private fun validate(toBankCode: BankCode) {
        if (toBankCode.isOurs) throw InvalidBankCodeException(toBankCode.value, "우리 은행 코드는 당행 송금으로")
    }

    // 1단계 의도 상태로 바꿔 첫 INSERT. 같은 키가 이미 있으면 DuplicateIdempotencyKeyException
    private fun start(transfer: InterbankTransfer): InterbankTransfer =
        save(transfer.apply { withdrawRequested() })

    private fun save(transfer: InterbankTransfer): InterbankTransfer {
        val saved = transactionTemplate.execute { repository.save(transfer) }
        if (saved.status.isFinal()) transferEventPublisher.publishFinished(saved)
        return saved
    }
}
