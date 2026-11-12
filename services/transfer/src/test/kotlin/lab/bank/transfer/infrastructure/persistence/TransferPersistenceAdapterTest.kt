package lab.bank.transfer.infrastructure.persistence

import lab.bank.transfer.TestcontainersConfiguration
import lab.bank.transfer.domain.Transfer
import lab.bank.transfer.domain.TransferStatus
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.orm.ObjectOptimisticLockingFailureException
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

// 저장 어댑터 테스트: 매퍼가 필드를 빠짐없이 옮기는지, 버전(낙관적 락)이 동작하는지, DB 제약이 걸려 있는지
//
// 상태를 바꾼 송금은 도메인 메서드(개발자 구현 예정) 대신 복원용 생성자로 직접 만든다 (저장 계층만 검사하려고)
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration::class, TransferPersistenceAdapter::class)
class TransferPersistenceAdapterTest {

    @Autowired lateinit var adapter: TransferPersistenceAdapter
    @Autowired lateinit var em: TestEntityManager

    private val now = Instant.parse("2026-11-13T00:00:00Z")

    private fun transfer(
        id: UUID = UUID.randomUUID(),
        from: UUID = UUID.randomUUID(),
        to: UUID = UUID.randomUUID(),
        amount: Long = 10_000,
        status: TransferStatus = TransferStatus.REQUESTED,
        failureCode: String? = null,
        coreEntryId: UUID? = null,
        version: Long? = null,
    ) = Transfer(id, from, to, amount, status, failureCode, coreEntryId, now, now, version)

    // 같은 송금의 "다음 상태"를 복원 생성자로 흉내 낸다 (나중엔 도메인 메서드가 할 일)
    private fun Transfer.withStatus(status: TransferStatus, failureCode: String? = null, coreEntryId: UUID? = null) =
        Transfer(id, fromAccountId, toAccountId, amount, status, failureCode, coreEntryId, createdAt, now, version)

    @Test
    fun `새 송금을 저장하고 조회하면 모든 필드가 그대로다`() {
        val saved = adapter.save(transfer(status = TransferStatus.PROCESSING))
        em.clear()   // 1차 캐시를 비워 DB에서 다시 읽게

        val found = assertNotNull(adapter.findById(saved.id))
        assertEquals(saved.fromAccountId, found.fromAccountId)
        assertEquals(saved.toAccountId, found.toAccountId)
        assertEquals(10_000, found.amount)
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

    @Test
    fun `같은 계좌로 보내는 송금은 DB 제약이 막는다`() {
        val accountId = UUID.randomUUID()
        assertFailsWith<DataIntegrityViolationException> { adapter.save(transfer(from = accountId, to = accountId)) }
    }

    @Test
    fun `완료인데 core 거래 ID가 없으면 DB 제약이 막는다`() {
        assertFailsWith<DataIntegrityViolationException> { adapter.save(transfer(status = TransferStatus.COMPLETED)) }
    }
}
