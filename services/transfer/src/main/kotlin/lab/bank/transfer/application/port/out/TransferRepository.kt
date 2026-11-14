package lab.bank.transfer.application.port.out

import lab.bank.transfer.domain.IdempotencyKey
import lab.bank.transfer.domain.Transfer
import lab.bank.transfer.domain.UserId
import java.util.UUID

/**
 * 송금 저장 포트 (아웃바운드). 유스케이스는 이 인터페이스만 알고 JPA는 모른다.
 * 구현: infrastructure/persistence/TransferPersistenceAdapter. 테스트에서는 메모리 구현으로 바꿔 끼울 수 있다.
 */
interface TransferRepository {

    /**
     * 저장하고, 저장된 상태(올라간 version 포함)를 돌려준다. **이후에는 반환값을 써야 한다.**
     * 인자로 넘긴 객체의 version은 저장 전 값이라, 그걸로 다시 저장하면 낙관적 락 충돌이 난다.
     * 그사이 다른 쪽이 먼저 바꿨으면 ObjectOptimisticLockingFailureException.
     * 새 송금인데 같은 요청자의 같은 고객 멱등 키가 이미 있으면 DuplicateIdempotencyKeyException.
     */
    fun save(transfer: Transfer): Transfer

    fun findById(id: UUID): Transfer?

    // 멱등 키는 요청자별로 유일하다 (Flyway V3) → 요청자 없이 키만으로는 찾지 않는다
    fun findByIdempotencyKey(requesterId: UserId, idempotencyKey: IdempotencyKey): Transfer?
}
