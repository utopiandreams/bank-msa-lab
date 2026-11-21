package lab.bank.core.account.infrastructure.lock

import org.redisson.Redisson
import org.redisson.api.RedissonClient
import org.redisson.config.Config
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Redis 분산 락용 클라이언트 (7주 차, REDIS 전략). account가 "트랜잭션 바깥 잠금"(AccountLocks)을 공개하고, posting은 트랜잭션을 그 안에서 연다.
 *
 * lazyInitialization: Redis에는 **처음 명령을 보낼 때** 붙는다. 이게 없으면 Redisson.create()가 앱 시작 때 바로 접속하고,
 * Redis가 없거나 죽어 있으면 core가 아예 안 뜬다. REDIS 전략을 안 쓰는데도 새 의존성이 기본 경로를 죽이는 셈이라 막는다
 * (다른 테스트들도 Redis 컨테이너 없이 그대로 돈다).
 *
 * 타임아웃: 외부 호출이라 반드시 건다 (코드 컨벤션). Redisson 기본값(접속 10초, 응답 3초 × 재시도 3번)은 송금 API에 너무 길다.
 */
@Configuration(proxyBeanMethods = false)
class RedissonConfig {

    @Bean(destroyMethod = "shutdown")
    fun redissonClient(
        @Value("\${core.redis.address}") address: String,
        @Value("\${core.redis.connect-timeout-ms}") connectTimeoutMs: Int,
        @Value("\${core.redis.timeout-ms}") timeoutMs: Int,
    ): RedissonClient {
        val config = Config().apply {
            setLazyInitialization(true)
            useSingleServer()
                .setAddress(address)
                .setConnectTimeout(connectTimeoutMs)
                .setTimeout(timeoutMs)
        }
        return Redisson.create(config)
    }
}
