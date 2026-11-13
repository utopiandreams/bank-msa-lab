package lab.bank.transfer

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary

// 진짜 어댑터(CorePostingAdapter) 대신 가짜 core를 쓰게 한다. @Import(FakeCoreConfiguration::class)
// 같은 설정을 쓰는 테스트 클래스끼리는 Spring 컨텍스트를 재사용해서 빠르다
@TestConfiguration(proxyBeanMethods = false)
class FakeCoreConfiguration {

    @Bean
    @Primary
    fun fakeCorePostingPort() = FakeCorePostingPort()
}
