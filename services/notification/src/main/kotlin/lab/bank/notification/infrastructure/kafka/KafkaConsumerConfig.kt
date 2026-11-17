package lab.bank.notification.infrastructure.kafka

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.listener.DefaultErrorHandler
import org.springframework.util.backoff.FixedBackOff

@Configuration
class KafkaConsumerConfig {

    /**
     * 처리 중 예외(DB 장애 같은 일시 오류) → 1초 간격으로 3번 더 시도, 그래도 실패하면 로그를 남기고 다음 메시지로.
     * 기본값은 간격 없이 9번이라 DB가 잠깐 끊긴 동안 순식간에 소진된다.
     * 건너뛴 메시지는 알림 유실이다 → 6주 차에 Dead Letter Topic을 검토한다.
     * Spring Boot가 이 빈을 리스너 컨테이너에 자동으로 붙인다.
     */
    @Bean
    fun kafkaErrorHandler() = DefaultErrorHandler(FixedBackOff(1_000L, 3L))
}
