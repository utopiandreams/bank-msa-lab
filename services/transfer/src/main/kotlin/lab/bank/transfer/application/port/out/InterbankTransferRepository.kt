package lab.bank.transfer.application.port.out

import lab.bank.transfer.domain.IdempotencyKey
import lab.bank.transfer.domain.InterbankTransfer
import lab.bank.transfer.domain.UserId
import java.time.Instant
import java.util.UUID

/** 타행 송금 저장 포트. 저장 규칙(반환값을 써야 함, 멱등 키 위반 번역)은 [TransferRepository]와 같다 */
interface InterbankTransferRepository {

    fun save(transfer: InterbankTransfer): InterbankTransfer

    fun findById(id: UUID): InterbankTransfer?

    fun findByIdempotencyKey(requesterId: UserId, idempotencyKey: IdempotencyKey): InterbankTransfer?

    /**
     * 해소 대상: 사가 중간 상태에 멈춘 타행 송금, 오래된 순(updatedAt)으로 최대 limit건 (설계 연습 08 2부 결정 2).
     *
     * 기준 시각 = 그 의도 상태가 된 시각(updatedAt). 재전송이 없어 그 상태에 있는 동안 updatedAt이 안 바뀐다
     * (**조건: 해소 쪽이 조회에 실패하면 행을 저장하지 않는다**. 같은 상태로 다시 저장하면 기준이 밀린다)
     *   - WITHDRAW_REQUESTED·WITHDRAWN·WITHDRAW_CANCEL_REQUESTED: updatedAt < coreSentBefore (core 홉: 트랜잭션 타임아웃 + 여유)
     *   - EXTERNAL_BANK_REQUESTED: updatedAt < externalSentBefore (외부 홉: **외부 처리 상한** + 여유)
     *
     * **트랜잭션 안에서만** 부른다: 잡은 행을 커밋까지 잠그고(FOR UPDATE), 다른 트랜잭션이 잠근 행은 건너뛴다(SKIP LOCKED)
     */
    fun findStuckForUpdate(coreSentBefore: Instant, externalSentBefore: Instant, limit: Int): List<InterbankTransfer>
}
