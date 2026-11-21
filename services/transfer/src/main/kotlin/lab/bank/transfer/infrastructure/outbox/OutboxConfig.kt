package lab.bank.transfer.infrastructure.outbox

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling

// @Scheduled(릴레이·정리)를 켠다. 스케줄러 스레드는 기본 1개라 릴레이와 정리가 겹치면 한쪽이 기다린다 (정리는 하루 한 번이라 문제없음)
@Configuration
@EnableScheduling
@EnableConfigurationProperties(OutboxProperties::class)
class OutboxConfig
