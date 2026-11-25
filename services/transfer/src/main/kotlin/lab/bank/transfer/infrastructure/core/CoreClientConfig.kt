package lab.bank.transfer.infrastructure.core

import io.micrometer.observation.ObservationRegistry
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
 * 직접 만든 빌더는 관측성에 안 붙는다 → observationRegistry를 넘겨야 http.client.requests 지표가 생긴다 (2026-11-25 발견:
 * 트레이스는 OTel 에이전트가 JDK HttpClient를 따로 잡아서 보였지만, 지표는 그동안 하나도 없었다).
 * 기본값 NOOP은 이 클래스를 직접 부르는 단위 테스트용.
 *
 * 타임아웃을 안 걸면 JDK HttpClient의 응답 대기는 무제한이다 → core가 멈추면 transfer 스레드도 영원히 묶인다 (CLAUDE.md: 외부 호출엔 반드시 타임아웃)
 */
@Configuration
@EnableConfigurationProperties(CoreClientProperties::class)
class CoreClientConfig {

    @Bean
    fun coreRestClient(
        properties: CoreClientProperties,
        observationRegistry: ObservationRegistry = ObservationRegistry.NOOP,
    ): RestClient {
        val httpClient = HttpClient.newBuilder()
            .connectTimeout(properties.connectTimeout)
            .build()
        val requestFactory = JdkClientHttpRequestFactory(httpClient).apply {
            setReadTimeout(properties.readTimeout)
        }
        return RestClient.builder()
            .baseUrl(properties.baseUrl)
            .requestFactory(requestFactory)
            .observationRegistry(observationRegistry)
            .build()
    }
}
