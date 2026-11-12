package lab.bank.transfer

import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import

// 컨텍스트가 뜨는지 + Flyway 마이그레이션과 JPA 엔티티가 맞는지(ddl-auto: validate) 확인
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class TransferApplicationTests {

    @Test
    fun contextLoads() {
    }
}
