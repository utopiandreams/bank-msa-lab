package lab.bank.notification

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.testcontainers.kafka.KafkaContainer
import org.testcontainers.postgresql.PostgreSQLContainer

// 진짜 Postgres + 진짜 Kafka. 계정·DB 이름은 클러스터(CloudNativePG notification-db)와 맞춘다 (search_path 문제 재현 조건)
@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    fun postgres() = PostgreSQLContainer("postgres:18")
        .withDatabaseName("notification")
        .withUsername("notification")

    @Bean
    @ServiceConnection
    fun kafka() = KafkaContainer("apache/kafka:4.3.2")   // 테스트용은 토픽 자동 생성이 켜져 있다
}
