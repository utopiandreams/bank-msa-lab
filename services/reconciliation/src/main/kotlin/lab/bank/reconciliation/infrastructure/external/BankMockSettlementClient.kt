package lab.bank.reconciliation.infrastructure.external

import lab.bank.reconciliation.application.ExternalSettlementClient
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.time.Duration
import java.time.LocalDate

/**
 * 외부 정산 파일 채널 = bank-mock GET /interbank/settlement-files/{date}. 실패하면 예외 → 수신 작업이 실패로 끝나고 다시 받는다.
 * 타임아웃 (CLAUDE.md 7장): 파일 하나라 connect 1s / read 10s
 */
@Component
class BankMockSettlementClient(
    @Value("\${reconciliation.external.base-url:http://bank-mock}") baseUrl: String,
) : ExternalSettlementClient {

    private val client = RestClient.builder()
        .baseUrl(baseUrl)
        .requestFactory(SimpleClientHttpRequestFactory().apply {
            setConnectTimeout(Duration.ofSeconds(1))
            setReadTimeout(Duration.ofSeconds(10))
        })
        .build()

    override fun fetch(date: LocalDate): ByteArray =
        client.get().uri("/interbank/settlement-files/{date}", date.toString()).retrieve().body(ByteArray::class.java)
            ?: ByteArray(0)
}
