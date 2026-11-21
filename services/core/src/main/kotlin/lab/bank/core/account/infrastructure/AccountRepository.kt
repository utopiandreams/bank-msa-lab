package lab.bank.core.account.infrastructure

import jakarta.persistence.LockModeType
import lab.bank.core.account.domain.Account
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

// 인터페이스만 선언하면 Spring Data JPA가 구현체를 만들어 준다 (save, findById, …)
// 메서드 이름으로 쿼리도 만들어 준다: findAllByOwnerId → WHERE owner_id = ?
interface AccountRepository : JpaRepository<Account, UUID> {
    fun findAllByOwnerId(ownerId: String): List<Account>

    // ── 락 전략별 (7주 차, account/infrastructure/balance/) ──

    // PESSIMISTIC: SELECT ... FOR UPDATE. 이 행을 잡은 트랜잭션이 끝날 때까지 다른 FOR UPDATE·UPDATE는 기다린다
    // 대기 시간 상한(lock_timeout)은 걸지 않았다 → 핫 계좌에서 대기가 쌓이면 커넥션 풀이 마른다. k6로 확인할 지점
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM Account a WHERE a.id = :id")
    fun findByIdForUpdate(@Param("id") id: UUID): Account?

    // NONE (재현용): 앱이 계산한 잔액을 버전 검사 없이 그대로 덮어쓴다 → 그사이 다른 트랜잭션이 쓴 값이 사라진다 (lost update)
    @Modifying
    @Query("UPDATE Account a SET a.balance = :balance, a.lastLedgerId = :ledgerEntryId, a.updatedAt = :now WHERE a.id = :id")
    fun overwriteBalance(
        @Param("id") id: UUID,
        @Param("balance") balance: Long,
        @Param("ledgerEntryId") ledgerEntryId: UUID,
        @Param("now") now: Instant,
    ): Int

    // ATOMIC_UPDATE: 엔티티를 읽지 않고 예금주만 본다 (소유자 검사용). 영속성 컨텍스트에 Account가 올라오지 않는다
    @Query("SELECT a.ownerId FROM Account a WHERE a.id = :id")
    fun findOwnerIdById(@Param("id") id: UUID): String?

    /**
     * ATOMIC_UPDATE 출금: "잔액이 충분하면 뺀다"를 **SQL 한 문장**으로. 반환값 = 바뀐 행 수 (1 = 출금됨, 0 = 조건 불만족)
     *
     * 왜 동시에 와도 안전한가 (PostgreSQL READ COMMITTED 기준):
     * 1. UPDATE는 대상 행에 쓰기 잠금을 건다. 같은 행을 UPDATE하려는 두 번째 트랜잭션은 첫 번째가 커밋할 때까지 기다린다
     * 2. 첫 번째가 커밋하면 두 번째는 **커밋된 최신 행으로 WHERE를 다시 평가**한다. 잔액이 모자라게 됐으면 0행 = 출금 안 됨
     * → "읽고 → 앱에서 판단 → 쓰기" 사이의 틈이 아예 없다. 판단과 변경이 DB 안에서 한 번에 일어난다
     * (REPEATABLE READ였다면 2에서 다시 평가하지 않고 직렬화 오류로 실패한다. MySQL InnoDB도 UPDATE는 최신 커밋 값을 읽어 같은 결과)
     *
     * JPQL 벌크 업데이트라 영속성 컨텍스트·더티 체킹을 거치지 않는다. 그래서:
     * - @Version이 저절로 오르지 않는다 → 직접 +1 (다른 경로가 OPTIMISTIC으로 같은 행을 읽고 있으면 충돌을 알아채게)
     * - 같은 트랜잭션에서 미리 읽어 둔 Account 객체가 있으면 옛 잔액을 들고 있다 → 이 전략은 Account를 아예 읽지 않는다
     */
    @Modifying
    @Query(
        """
        UPDATE Account a
           SET a.balance = a.balance - :amount, a.lastLedgerId = :ledgerEntryId, a.updatedAt = :now, a.version = a.version + 1
         WHERE a.id = :id AND a.balance >= :amount
        """
    )
    fun withdrawAtomically(
        @Param("id") id: UUID,
        @Param("amount") amount: Long,
        @Param("ledgerEntryId") ledgerEntryId: UUID,
        @Param("now") now: Instant,
    ): Int

    // ATOMIC_UPDATE 입금: 조건 없음. 0행이면 계좌가 없는 것
    @Modifying
    @Query(
        """
        UPDATE Account a
           SET a.balance = a.balance + :amount, a.lastLedgerId = :ledgerEntryId, a.updatedAt = :now, a.version = a.version + 1
         WHERE a.id = :id
        """
    )
    fun depositAtomically(
        @Param("id") id: UUID,
        @Param("amount") amount: Long,
        @Param("ledgerEntryId") ledgerEntryId: UUID,
        @Param("now") now: Instant,
    ): Int
}
