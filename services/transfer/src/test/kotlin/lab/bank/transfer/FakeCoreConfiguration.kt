package lab.bank.transfer

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary

// 진짜 어댑터 대신 가짜 외부 의존을 쓰게 한다: core(CorePostingAdapter)와 Kafka(KafkaTransferEventPublisher). @Import(FakeCoreConfiguration::class)
// 같은 설정을 쓰는 테스트 클래스끼리는 Spring 컨텍스트를 재사용해서 빠르다
@TestConfiguration(proxyBeanMethods = false)
class FakeCoreConfiguration {

    @Bean
    @Primary
    fun fakeCorePostingPort() = FakeCorePostingPort()

    // 이게 없으면 송금 테스트마다 진짜 Kafka(localhost)에 붙으려다 max.block.ms(1초)씩 기다린다
    @Bean
    @Primary
    fun fakeTransferEventPublisher() = FakeTransferEventPublisher()
}
