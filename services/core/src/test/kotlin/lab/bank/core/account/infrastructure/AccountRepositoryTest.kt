package lab.bank.core.account.infrastructure

import lab.bank.core.TestcontainersConfiguration
import lab.bank.core.account.domain.Account
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager
import org.springframework.context.annotation.Import
import java.util.UUID
import kotlin.test.assertEquals

// 영속성 통합 테스트: 진짜 Postgres(Testcontainers)에 붙어서 저장·조회·매핑을 검사한다 (느리다, 초 단위)
// @DataJpaTest: JPA 관련 빈만 띄운다 (컨트롤러 등은 안 띄움). 테스트마다 트랜잭션을 걸고 끝나면 롤백
// @AutoConfigureTestDatabase(NONE): 내장 DB(H2)로 바꿔치기하지 말고 Testcontainers의 Postgres를 쓴다
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration::class)
class AccountRepositoryTest {

    @Autowired
    lateinit var accountRepository: AccountRepository

    // flush(쌓인 SQL을 DB로 보내기), clear(1차 캐시 비우기)를 테스트에서 직접 하려고 쓴다
    @Autowired
    lateinit var em: TestEntityManager

    @Test
    fun `잔액 변경이 DB에 반영되고 version이 오른다`() {
        // given: 저장된 계좌 (INSERT 실행, version 0)
        val account = accountRepository.saveAndFlush(Account(ownerId = "u-1"))
        assertEquals(0L, account.version)

        // when: 입금만 하고 save()는 부르지 않는다
        account.deposit(10_000L, UUID.randomUUID())
        em.flush()   // 더티 체킹: 트랜잭션 안에서 바뀐 엔티티를 찾아 UPDATE를 보낸다 (version도 +1)
        em.clear()   // 1차 캐시를 비워서, 다음 조회가 메모리가 아니라 DB에서 읽게 한다

        // then: DB에서 새로 읽은 값
        val reloaded = accountRepository.findById(account.id).orElseThrow()
        assertEquals(10_000L, reloaded.balance)
        assertEquals(1L, reloaded.version)
    }

    @Test
    fun `예금주 ID로 계좌 목록을 조회한다`() {
        // given: u-1 계좌 2개, u-2 계좌 1개
        accountRepository.saveAll(
            listOf(Account(ownerId = "u-1"), Account(ownerId = "u-1"), Account(ownerId = "u-2")),
        )
        em.flush()
        em.clear()

        // when
        val accounts = accountRepository.findAllByOwnerId("u-1")

        // then: 정확히 2개
        // 위 테스트도 u-1 계좌를 만든다. 테스트가 끝날 때 롤백되지 않았다면 3개가 나온다 → 롤백의 증거이기도 하다
        assertEquals(2, accounts.size)
        assertEquals(setOf("u-1"), accounts.map { it.ownerId }.toSet())
    }
}
