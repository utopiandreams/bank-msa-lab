package lab.bank.transfer

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.testcontainers.postgresql.PostgreSQLContainer

// 테스트용 Postgres를 Docker로 띄운다 (core와 같은 구성). 테스트 클래스에서 @Import(TestcontainersConfiguration::class)
@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    fun postgres() = PostgreSQLContainer("postgres:18")
}
