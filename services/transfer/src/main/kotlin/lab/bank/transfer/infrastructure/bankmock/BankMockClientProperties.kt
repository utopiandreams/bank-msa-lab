package lab.bank.transfer.infrastructure.bankmock

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * 외부 결제망(bank-mock) 호출 설정 (application.yaml의 bank-mock.*). 15주 차 "외부 은행 3초 지연" 실험에서 read-timeout을 바꿔 본다.
 * read-timeout을 넘기면 외부가 **처리했는지 모른다** → Unknown → 보상하지 않는다 (ADR-0004)
 */
@ConfigurationProperties(prefix = "bank-mock")
data class BankMockClientProperties(
    val baseUrl: String,
    val connectTimeout: Duration,
    val readTimeout: Duration,
)
