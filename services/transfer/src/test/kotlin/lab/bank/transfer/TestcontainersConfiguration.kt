package lab.bank.transfer

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.testcontainers.postgresql.PostgreSQLContainer

// 테스트용 Postgres를 Docker로 띄운다 (core와 같은 구성). 테스트 클래스에서 @Import(TestcontainersConfiguration::class)
// 계정·DB 이름을 클러스터(CloudNativePG transfer-db)와 맞춘다: 계정 이름 = 스키마 이름(transfer)일 때만 생기는
// search_path("$user", public) 문제가 있었다 (FlywayRestartTest, 2026-11-14)
@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    fun postgres() = PostgreSQLContainer("postgres:18")
        .withDatabaseName("transfer")
        .withUsername("transfer")
}
