package lab.bank.transfer.infrastructure.core

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.web.client.RestClient
import java.net.http.HttpClient

/**
 * core 전용 RestClient.
 *
 * Boot 4는 RestClient 자동 설정이 별도 모듈(spring-boot-restclient)이라 지금은 직접 만든다 (라이브러리 추가 없이).
 * 9~10주 차에 트레이스·지표를 붙일 때 자동 설정 모듈로 옮기는 것을 검토한다 (자동 설정 빌더에 관측성 연동이 들어 있다).
 *
 * 타임아웃을 안 걸면 JDK HttpClient의 응답 대기는 무제한이다 → core가 멈추면 transfer 스레드도 영원히 묶인다 (CLAUDE.md: 외부 호출엔 반드시 타임아웃)
 */
@Configuration
@EnableConfigurationProperties(CoreClientProperties::class)
class CoreClientConfig {

    @Bean
    fun coreRestClient(properties: CoreClientProperties): RestClient {
        val httpClient = HttpClient.newBuilder()
            .connectTimeout(properties.connectTimeout)
            .build()
        val requestFactory = JdkClientHttpRequestFactory(httpClient).apply {
            setReadTimeout(properties.readTimeout)
        }
        return RestClient.builder()
            .baseUrl(properties.baseUrl)
            .requestFactory(requestFactory)
            .build()
    }
}
