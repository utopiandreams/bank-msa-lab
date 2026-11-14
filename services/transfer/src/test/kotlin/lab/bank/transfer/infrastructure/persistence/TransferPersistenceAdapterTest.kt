package lab.bank.transfer.infrastructure.persistence

import lab.bank.transfer.TestcontainersConfiguration
import lab.bank.transfer.application.port.out.DuplicateIdempotencyKeyException
import lab.bank.transfer.domain.Amount
import lab.bank.transfer.domain.IdempotencyKey
import lab.bank.transfer.domain.Transfer
import lab.bank.transfer.domain.UserId
import lab.bank.transfer.domain.TransferStatus
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.orm.ObjectOptimisticLockingFailureException
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

// 저장 어댑터 테스트: 매퍼가 필드를 빠짐없이 옮기는지, 버전(낙관적 락)이 동작하는지, DB 제약이 걸려 있는지
//
// 상태를 바꾼 송금은 도메인 메서드 대신 복원용 생성자로 직접 만든다 (전이 규칙과 무관하게 저장 계층만 검사하려고)
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration::class, TransferPersistenceAdapter::class)
class TransferPersistenceAdapterTest {

    @Autowired lateinit var adapter: TransferPersistenceAdapter
    @Autowired lateinit var em: TestEntityManager
    @Autowired lateinit var jdbc: JdbcTemplate

    private val now = Instant.parse("2026-11-13T00:00:00Z")

    private fun transfer(
        id: UUID = UUID.randomUUID(),
        key: String = "key-${UUID.randomUUID()}",
        requester: String = "chulsoo",
        from: UUID = UUID.randomUUID(),
        to: UUID = UUID.randomUUID(),
        amount: Long = 10_000,
        status: TransferStatus = TransferStatus.REQUESTED,
        failureCode: String? = null,
        coreEntryId: UUID? = null,
        version: Long? = null,
    ) = Transfer(id, UserId(requester), IdempotencyKey(key), from, to, Amount(amount), status, failureCode, coreEntryId, now, now, version)

    // 같은 송금의 "다음 상태"를 복원 생성자로 흉내 낸다 (UNKNOWN → FAILED 경합처럼 전이 순서를 자유롭게 만들려고)
    private fun Transfer.withStatus(status: TransferStatus, failureCode: String? = null, coreEntryId: UUID? = null) =
        Transfer(id, requesterId, idempotencyKey, fromAccountId, toAccountId, amount, status, failureCode, coreEntryId, createdAt, now, version)

    @Test
    fun `새 송금을 저장하고 조회하면 모든 필드가 그대로다`() {
        val saved = adapter.save(transfer(status = TransferStatus.PROCESSING))
        em.clear()   // 1차 캐시를 비워 DB에서 다시 읽게

        val found = assertNotNull(adapter.findById(saved.id))
        assertEquals(saved.fromAccountId, found.fromAccountId)
        assertEquals(saved.toAccountId, found.toAccountId)
        assertEquals(Amount(10_000), found.amount)
        assertEquals(TransferStatus.PROCESSING, found.status)
        assertEquals(now, found.createdAt)
        assertEquals(0L, found.version)   // 새 엔티티: null → INSERT 후 0
    }

    @Test
    fun `상태를 바꿔 저장하면 version이 오르고 완료 정보가 남는다`() {
        val saved = adapter.save(transfer(status = TransferStatus.PROCESSING))
        val entryId = UUID.randomUUID()

        val completed = adapter.save(saved.withStatus(TransferStatus.COMPLETED, coreEntryId = entryId))
        em.clear()

        assertEquals(1L, completed.version)   // 반환값에 올라간 버전이 들어 있다
        val found = assertNotNull(adapter.findById(saved.id))
        assertEquals(TransferStatus.COMPLETED, found.status)
        assertEquals(entryId, found.coreEntryId)
    }

    @Test
    fun `옛 버전으로 저장하면 낙관적 락 예외 - 그사이 다른 쪽이 먼저 바꾼 경우`() {
        // 8주 차 상황: 요청 스레드와 해소 스케줄러가 같은 송금을 동시에 결과 반영
        val saved = adapter.save(transfer(status = TransferStatus.UNKNOWN))   // version 0
        adapter.save(saved.withStatus(TransferStatus.COMPLETED, coreEntryId = UUID.randomUUID()))   // 먼저 반영 → version 1

        assertFailsWith<ObjectOptimisticLockingFailureException> {
            adapter.save(saved.withStatus(TransferStatus.FAILED, failureCode = "INSUFFICIENT_BALANCE"))   // 아직 version 0
        }
    }

    @Test
    fun `없는 송금 ID를 조회하면 null`() {
        assertNull(adapter.findById(UUID.randomUUID()))
    }

    // 도메인(init의 require)이 먼저 막으므로 Transfer 객체로는 DB까지 못 간다 → SQL로 직접 넣어 마지막 방어선만 검사
    @Test
    fun `같은 계좌로 보내는 송금은 DB 제약이 막는다`() {
        val accountId = UUID.randomUUID()
        assertFailsWith<DataIntegrityViolationException> {
            jdbc.update(
                """
                INSERT INTO transfer.transfer (id, idempotency_key, from_account_id, to_account_id, amount, status, created_at, updated_at, version)
                VALUES (?, ?, ?, ?, 10000, 'REQUESTED', now(), now(), 0)
                """.trimIndent(),
                UUID.randomUUID(), "key-${UUID.randomUUID()}", accountId, accountId,
            )
        }
    }

    @Test
    fun `완료인데 core 거래 ID가 없으면 DB 제약이 막는다`() {
        assertFailsWith<DataIntegrityViolationException> { adapter.save(transfer(status = TransferStatus.COMPLETED)) }
    }

    // ───────────── 고객 멱등 키 (T-2) ─────────────

    @Test
    fun `멱등 키로 송금을 찾는다`() {
        val saved = adapter.save(transfer(key = "find-me", status = TransferStatus.PROCESSING))
        em.clear()

        val found = assertNotNull(adapter.findByIdempotencyKey(UserId("chulsoo"), IdempotencyKey("find-me")))
        assertEquals(saved.id, found.id)
        assertEquals(UserId("chulsoo"), found.requesterId)
        assertEquals(IdempotencyKey("find-me"), found.idempotencyKey)
    }

    @Test
    fun `멱등 키는 요청자별 - 다른 사용자의 같은 키로는 찾지 못한다`() {
        adapter.save(transfer(key = "shared-key", requester = "chulsoo", status = TransferStatus.PROCESSING))
        em.clear()

        assertNull(adapter.findByIdempotencyKey(UserId("younghee"), IdempotencyKey("shared-key")))
    }

    @Test
    fun `다른 사용자는 같은 멱등 키로 저장할 수 있다 - UNIQUE는 (요청자, 키)`() {
        adapter.save(transfer(key = "shared-key-2", requester = "chulsoo", status = TransferStatus.PROCESSING))

        adapter.save(transfer(key = "shared-key-2", requester = "younghee", status = TransferStatus.PROCESSING))   // 예외 없음
    }

    @Test
    fun `같은 멱등 키로 새 송금을 저장하면 - 포트 예외로 번역된다 (use case가 JPA 예외를 몰라도 되게)`() {
        adapter.save(transfer(key = "dup-key", status = TransferStatus.PROCESSING))

        val e = assertFailsWith<DuplicateIdempotencyKeyException> {
            adapter.save(transfer(key = "dup-key", status = TransferStatus.PROCESSING))
        }
        assertEquals(IdempotencyKey("dup-key"), e.idempotencyKey)
    }

    @Test
    fun `다른 제약 위반은 멱등 키 예외로 번역하지 않는다 - 우리 버그는 그대로 500`() {
        // 완료인데 원장 ID 없음 → ck_transfer_completed_has_entry 위반. 중복 키로 오인하면 안 된다
        // 번역됐다면 DuplicateIdempotencyKeyException(DataIntegrityViolationException 아님)이 나서 여기서 실패한다
        assertFailsWith<DataIntegrityViolationException> { adapter.save(transfer(status = TransferStatus.COMPLETED)) }
    }
}
