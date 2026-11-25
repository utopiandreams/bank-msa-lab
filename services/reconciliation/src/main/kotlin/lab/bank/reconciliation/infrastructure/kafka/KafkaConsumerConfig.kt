package lab.bank.reconciliation.infrastructure.kafka

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.listener.DefaultErrorHandler
import org.springframework.util.backoff.FixedBackOff

@Configuration
class KafkaConsumerConfig {

    /** 인박스 기록 중 일시 오류(DB 장애) → 1초 간격 3번 더. notification과 같은 설정 */
    @Bean
    fun kafkaErrorHandler() = DefaultErrorHandler(FixedBackOff(1_000L, 3L))
}
