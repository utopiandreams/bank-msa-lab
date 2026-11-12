package lab.bank.transfer.application.port.out

import lab.bank.transfer.domain.Transfer
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
     */
    fun save(transfer: Transfer): Transfer

    fun findById(id: UUID): Transfer?
}
