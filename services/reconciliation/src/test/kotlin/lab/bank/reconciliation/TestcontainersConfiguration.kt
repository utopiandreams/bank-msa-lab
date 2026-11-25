package lab.bank.reconciliation

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.testcontainers.kafka.KafkaContainer
import org.testcontainers.postgresql.PostgreSQLContainer

// 진짜 Postgres + 진짜 Kafka. 계정·DB 이름은 클러스터(CloudNativePG reconciliation-db)와 맞춘다 (search_path 문제 재현 조건)
@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    fun postgres() = PostgreSQLContainer("postgres:18")
        .withDatabaseName("reconciliation")
        .withUsername("reconciliation")

    @Bean
    @ServiceConnection
    fun kafka() = KafkaContainer("apache/kafka:4.3.2")   // 추출 완료 이벤트 구독용
}
