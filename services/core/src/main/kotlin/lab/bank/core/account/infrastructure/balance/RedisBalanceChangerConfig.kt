package lab.bank.core.account.infrastructure.balance

import lab.bank.core.account.application.balance.LockStrategy
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

// REDIS와 REDIS_SPIN은 잔액 변경 코드가 같고(OPTIMISTIC + Redis 락은 바깥에서), 다른 건 AccountLocks가 잡는 락 종류뿐
@Configuration(proxyBeanMethods = false)
class RedisBalanceChangerConfig {

    @Bean
    fun redisBalanceChanger(optimistic: OptimisticBalanceChanger) = RedisGuardedBalanceChanger(LockStrategy.REDIS, optimistic)

    @Bean
    fun redisSpinBalanceChanger(optimistic: OptimisticBalanceChanger) = RedisGuardedBalanceChanger(LockStrategy.REDIS_SPIN, optimistic)
}
