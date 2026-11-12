package lab.bank.core.ledger.infrastructure

import lab.bank.core.TestcontainersConfiguration
import lab.bank.core.ledger.domain.Direction
import lab.bank.core.ledger.domain.JournalEntry
import lab.bank.core.ledger.domain.JournalLine
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.repository.findByIdOrNull
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// 영속성 통합 테스트: 거래-분개 줄 양방향 매핑, 멱등 키 UNIQUE, 지연 로딩을 진짜 Postgres에서 확인한다
// SQL을 눈으로 보려면 실행할 때: ./gradlew :services:core:test --tests '*JournalEntryRepositoryTest*' -i
//   (또는 src/test/resources/application.yaml에 spring.jpa.show-sql: true)
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration::class)
class JournalEntryRepositoryTest {

    @Autowired
    lateinit var journalEntryRepository: JournalEntryRepository

    @Autowired
    lateinit var em: TestEntityManager

    // 저장 → flush(SQL 전송) → clear(1차 캐시 비움). 이후 조회가 메모리가 아니라 DB에서 읽게 한다
    private fun saveAndClear(entry: JournalEntry) {
        journalEntryRepository.saveAndFlush(entry)
        em.clear()
    }

    @Test
    fun `입금 거래를 저장하면 거래 1행과 분개 줄 2행이 들어간다`() {
        val journal = JournalEntry.deposit("key-1", UUID.randomUUID(), 100_000)
        saveAndClear(journal)

        // findByIdOrNull: Optional 대신 null을 주는 Spring Data의 Kotlin 확장 함수
        // kotlin.test.assertNotNull은 null이 아니면 그 값을 non-null 타입으로 돌려준다 → 아래에서 .get()이나 ?. 없이 쓴다
        val saved = assertNotNull(journalEntryRepository.findByIdOrNull(journal.id))

        assertEquals(2, saved.lines.size)
        // 줄 순서는 보장되지 않아서(@OrderColumn 없음) lines[0] 대신 all로 검사한다
        assertTrue(saved.lines.all { it.entry.id == journal.id })
    }

    @Test
    fun `저장된 거래의 차변 합과 대변 합이 같다`() {
        val journal = JournalEntry.transfer("key-1", UUID.randomUUID(), UUID.randomUUID(), 100_000)
        saveAndClear(journal)

        val saved = assertNotNull(journalEntryRepository.findByIdOrNull(journal.id))

        // DB에서 읽은 줄 기준: 메모리의 객체가 아니라 실제로 저장된 값이 균형인지
        val debit = saved.lines.filter { it.direction == Direction.DEBIT }.sumOf { it.amount }
        val credit = saved.lines.filter { it.direction == Direction.CREDIT }.sumOf { it.amount }
        assertEquals(100_000, debit)
        assertEquals(debit, credit)
    }

    @Test
    fun `같은 멱등 키로 거래를 두 번 저장하면 UNIQUE 위반이 난다`() {
        val accountId = UUID.randomUUID()
        journalEntryRepository.saveAndFlush(JournalEntry.deposit("key-1", accountId, 10_000))

        // 재시도로 같은 요청이 한 번 더 들어온 상황: 거래 ID는 새로 만들어졌지만 멱등 키가 같다
        // Hibernate/JDBC 예외를 Spring이 DB 종류와 무관한 DataIntegrityViolationException 계열로 바꿔 준다
        // → posting은 이 타입을 잡아서 "이미 처리됨"으로 바꾼다 (PostgreSQL 에러 코드에 묶이지 않는다)
        assertFailsWith<DataIntegrityViolationException> {
            journalEntryRepository.saveAndFlush(JournalEntry.deposit("key-1", accountId, 10_000))
        }
    }

    @Test
    fun `멱등 키로 거래를 조회한다`() {
        val journal = JournalEntry.deposit("key-1", UUID.randomUUID(), 10_000)
        saveAndClear(journal)

        assertEquals(journal.id, journalEntryRepository.findByIdempotencyKey("key-1")?.id)
        assertNull(journalEntryRepository.findByIdempotencyKey("key-없음"))
    }

    @Test
    fun `거래를 조회해도 분개 줄은 접근하기 전까지 로딩되지 않는다`() {
        val journal = JournalEntry.deposit("key-1", UUID.randomUUID(), 10_000)
        saveAndClear(journal)
        val util = em.entityManager.entityManagerFactory.persistenceUnitUtil

        // 거래 → 줄 (컬렉션): 접근 전에는 안 읽고, 접근하면 그때 SELECT
        val saved = assertNotNull(journalEntryRepository.findByIdOrNull(journal.id))
        assertFalse(util.isLoaded(saved, "_lines"))
        saved.lines.size
        assertTrue(util.isLoaded(saved, "_lines"))
    }

    @Test
    fun `분개 줄만 조회하면 소속 거래는 지연 로딩된다`() {
        val journal = JournalEntry.deposit("key-1", UUID.randomUUID(), 10_000)
        val lineId = journal.lines.first().id
        saveAndClear(journal)
        val util = em.entityManager.entityManagerFactory.persistenceUnitUtil

        // 줄 → 거래 (@ManyToOne LAZY): 프록시가 필요하다. 클래스가 final이면 Hibernate가 프록시를 못 만들 수 있다
        val line = em.find(JournalLine::class.java, lineId)
        assertFalse(util.isLoaded(line, "entry"))
    }
}
