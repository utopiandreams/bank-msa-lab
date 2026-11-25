package lab.bank.reconciliation

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import

/**
 * 뼈대 확인 + 재기동 재현 (transfer·notification FlywayRestartTest와 같은 이유): 계정 이름 = 스키마 이름(reconciliation)이면
 * search_path("$user", public) 때문에 두 번째 기동부터 "기록 없는 비어 있지 않은 스키마"로 실패할 수 있다.
 * application.yaml의 spring.flyway.default-schema: public이 막는지 확인한다. 컨텍스트가 뜨는 것 자체가 뼈대 확인
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class FlywayRestartTest {

    @Autowired lateinit var flyway: Flyway

    @Test
    fun `이미 마이그레이션된 DB에서 다시 기동해도 Flyway가 통과한다`() {
        flyway.migrate()
    }
}
