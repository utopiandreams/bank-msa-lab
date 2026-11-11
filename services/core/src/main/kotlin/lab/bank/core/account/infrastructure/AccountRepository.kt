package lab.bank.core.account.infrastructure

import lab.bank.core.account.domain.Account
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

// 인터페이스만 선언하면 Spring Data JPA가 구현체를 만들어 준다 (save, findById, …)
// 메서드 이름으로 쿼리도 만들어 준다: findAllByOwnerId → WHERE owner_id = ?
interface AccountRepository : JpaRepository<Account, UUID> {
    fun findAllByOwnerId(ownerId: String): List<Account>
}
