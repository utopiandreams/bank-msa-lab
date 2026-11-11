package lab.bank.core

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.testcontainers.postgresql.PostgreSQLContainer

// 테스트용 Postgres를 Docker로 띄운다. 테스트 클래스에서 @Import(TestcontainersConfiguration::class)로 쓴다
// @ServiceConnection: 컨테이너의 주소·계정을 spring.datasource.*에 자동으로 꽂아 준다
@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    fun postgres() = PostgreSQLContainer("postgres:18")   // 클러스터(CloudNativePG 18.6)와 메이저 버전을 맞춘다
}
