package lab.bank.transfer

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import

/**
 * 재기동 재현: 이미 마이그레이션된 DB에서 앱이 다시 뜰 때 Flyway가 통과하는가.
 *
 * 2026-11-14 클러스터 장애: 첫 기동은 성공, 두 번째 기동부터 CrashLoopBackOff
 *   "Found non-empty schema(s) "transfer" but no schema history table"
 * 원인: search_path가 "$user", public이라 계정 이름과 같은 transfer 스키마가 생긴 뒤로는 Flyway가 기본 스키마를
 *   public → transfer로 바꿔 판단했다. 기록 테이블은 첫 기동 때 public에 만들어져 있었다.
 * 테스트가 못 잡은 이유: Testcontainers 계정이 test였고(조건 불성립), 매번 새 DB에서 한 번만 기동했다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class FlywayRestartTest {

    @Autowired lateinit var flyway: Flyway

    @Test
    fun `이미 마이그레이션된 DB에서 다시 기동해도 Flyway가 통과한다`() {
        // 컨텍스트가 뜨면서 한 번 마이그레이션됐다. 재기동을 흉내 내 한 번 더 실행한다
        flyway.migrate()
    }
}
