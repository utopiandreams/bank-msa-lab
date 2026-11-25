package lab.bank.bankmock

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.testcontainers.postgresql.PostgreSQLContainer

// 진짜 Postgres. 계정·DB 이름은 클러스터(CloudNativePG bank-mock-db)와 맞춘다 (계정 = 스키마 이름일 때의 search_path 문제 재현 조건)
@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    fun postgres() = PostgreSQLContainer("postgres:18")
        .withDatabaseName("bankmock")
        .withUsername("bankmock")
}
