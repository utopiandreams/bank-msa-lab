package lab.bank.transfer.infrastructure.persistence

import lab.bank.transfer.TestcontainersConfiguration
import lab.bank.transfer.application.port.out.DuplicateIdempotencyKeyException
import lab.bank.transfer.domain.Amount
import lab.bank.transfer.domain.BankCode
import lab.bank.transfer.domain.IdempotencyKey
import lab.bank.transfer.domain.InterbankTransfer
import lab.bank.transfer.domain.InterbankTransferStatus
import lab.bank.transfer.domain.Transfer
import lab.bank.transfer.domain.TransferStatus
import lab.bank.transfer.domain.UserId
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.orm.ObjectOptimisticLockingFailureException
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

// 타행 저장 어댑터: 두 테이블(공통 + 상세)에 나눠 저장되는지, 다시 읽으면 그대로인지, 당행 행과 섞이지 않는지
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration::class, InterbankTransferPersistenceAdapter::class, TransferPersistenceAdapter::class)
class InterbankTransferPersistenceAdapterTest {

    @Autowired lateinit var adapter: InterbankTransferPersistenceAdapter
    @Autowired lateinit var domesticAdapter: TransferPersistenceAdapter
    @Autowired lateinit var em: TestEntityManager
    @Autowired lateinit var jdbc: JdbcTemplate

    private fun newTransfer(key: String = "key-${UUID.randomUUID()}", requester: String = "chulsoo") =
        InterbankTransfer.request(UserId(requester), IdempotencyKey(key), UUID.randomUUID(), BankCode("004"), "123-456", Amount(10_000))

    @Test
    fun `새 타행 송금을 저장하면 공통·상세 두 테이블에 들어가고, 다시 읽으면 그대로다`() {
        val t = newTransfer().apply { withdrawRequested() }
        val saved = adapter.save(t)
        em.clear()

        val kind = jdbc.queryForObject("SELECT kind FROM transfer.transfer WHERE id = ?", String::class.java, t.id)
        val toAccountId = jdbc.queryForObject("SELECT to_account_id FROM transfer.transfer WHERE id = ?", String::class.java, t.id)
        val detailCount = jdbc.queryForObject("SELECT count(*) FROM transfer.interbank_transfer WHERE transfer_id = ?", Int::class.java, t.id)
        assertEquals("INTERBANK", kind)
        assertNull(toAccountId)   // 당행 전용 칸 (V4 CHECK: 타행이면 NULL)
        assertEquals(1, detailCount)

        val found = assertNotNull(adapter.findById(t.id))
        assertEquals(InterbankTransferStatus.WITHDRAW_REQUESTED, found.status)
        assertEquals(BankCode("004"), found.toBankCode)
        assertEquals("123-456", found.toAccountId)
        assertEquals(Amount(10_000), found.amount)
        assertEquals(saved.version, found.version)
    }

    @Test
    fun `사가 끝까지 저장하면 출금·취소 전표와 외부 사유가 남는다`() {
        var t = adapter.save(newTransfer().apply { withdrawRequested() })
        val withdrawEntry = UUID.randomUUID()
        val cancelEntry = UUID.randomUUID()

        t.withdrawn(withdrawEntry); t = adapter.save(t)
        t.externalBankRequested(); t = adapter.save(t)
        t.externalBankRejected("ACCOUNT_NOT_FOUND"); t = adapter.save(t)
        t.cancelled(cancelEntry); adapter.save(t)
        em.clear()

        val found = assertNotNull(adapter.findById(t.id))
        assertEquals(InterbankTransferStatus.CANCELLED, found.status)
        assertEquals(withdrawEntry, found.coreEntryId)
        assertEquals(cancelEntry, found.cancelEntryId)
        assertEquals("ACCOUNT_NOT_FOUND", found.externalRejectReason)
    }

    @Test
    fun `낡은 버전으로 저장하면 낙관적 락 예외`() {
        val first = adapter.save(newTransfer().apply { withdrawRequested() })
        val stale = adapter.findById(first.id)!!

        first.withdrawn(UUID.randomUUID()); adapter.save(first)
        stale.withdrawRejected("INSUFFICIENT_BALANCE")

        assertFailsWith<ObjectOptimisticLockingFailureException> { adapter.save(stale) }
    }

    @Test
    fun `멱등 키 조회 - 요청자별`() {
        val t = adapter.save(newTransfer(key = "same-key", requester = "chulsoo").apply { withdrawRequested() })

        assertEquals(t.id, adapter.findByIdempotencyKey(UserId("chulsoo"), IdempotencyKey("same-key"))?.id)
        assertNull(adapter.findByIdempotencyKey(UserId("younghee"), IdempotencyKey("same-key")))
    }

    @Test
    fun `같은 요청자·같은 키가 당행에 이미 있으면 중복 예외 - 키 UNIQUE가 당행·타행을 통틀어 걸린다`() {
        val now = Instant.now()
        domesticAdapter.save(
            Transfer(UUID.randomUUID(), UserId("chulsoo"), IdempotencyKey("shared-key"), UUID.randomUUID(), UUID.randomUUID(),
                Amount(1_000), TransferStatus.PROCESSING, null, null, now, now, null)
        )

        assertFailsWith<DuplicateIdempotencyKeyException> {
            adapter.save(newTransfer(key = "shared-key", requester = "chulsoo").apply { withdrawRequested() })
        }
    }

    @Test
    fun `타행 조회는 당행 행을 보지 않는다`() {
        val now = Instant.now()
        val domestic = domesticAdapter.save(
            Transfer(UUID.randomUUID(), UserId("chulsoo"), IdempotencyKey("domestic-key"), UUID.randomUUID(), UUID.randomUUID(),
                Amount(1_000), TransferStatus.PROCESSING, null, null, now, now, null)
        )
        em.clear()

        assertNull(adapter.findById(domestic.id))
        assertNull(adapter.findByIdempotencyKey(UserId("chulsoo"), IdempotencyKey("domestic-key")))
    }
}
