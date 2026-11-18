package lab.bank.transfer.infrastructure.bankmock

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.web.client.RestClient
import java.net.http.HttpClient

// bank-mock 전용 RestClient. core와 타임아웃을 따로 둔다: 외부 결제망은 내부 서비스보다 느리고, 실험에서 따로 바꾼다
@Configuration
@EnableConfigurationProperties(BankMockClientProperties::class)
class BankMockClientConfig {

    @Bean
    fun bankMockRestClient(properties: BankMockClientProperties): RestClient {
        val httpClient = HttpClient.newBuilder().connectTimeout(properties.connectTimeout).build()
        val requestFactory = JdkClientHttpRequestFactory(httpClient).apply { setReadTimeout(properties.readTimeout) }
        return RestClient.builder().baseUrl(properties.baseUrl).requestFactory(requestFactory).build()
    }
}
