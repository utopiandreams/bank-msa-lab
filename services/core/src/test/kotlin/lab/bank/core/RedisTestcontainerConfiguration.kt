package lab.bank.core

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.test.context.DynamicPropertyRegistrar
import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName

/**
 * 테스트용 Redis (REDIS 전략을 쓰는 테스트만 @Import). 클러스터(deploy/platform/redis)와 같은 이미지.
 * @ServiceConnection을 못 쓰는 이유: Boot의 Redis 자동 연결은 Spring Data Redis용이고, 우리는 Redisson을 직접 만든다
 * → 컨테이너 주소를 core.redis.address에 직접 넣는다. Redis 전용 Testcontainers 모듈은 새 라이브러리라 GenericContainer로
 */
@TestConfiguration(proxyBeanMethods = false)
class RedisTestcontainerConfiguration {

    @Bean
    fun redis(): GenericContainer<*> = GenericContainer(DockerImageName.parse("redis:8.2.11")).withExposedPorts(6379)

    @Bean
    fun redisAddress(redis: GenericContainer<*>) = DynamicPropertyRegistrar { registry ->
        registry.add("core.redis.address") { "redis://${redis.host}:${redis.getMappedPort(6379)}" }
    }
}
