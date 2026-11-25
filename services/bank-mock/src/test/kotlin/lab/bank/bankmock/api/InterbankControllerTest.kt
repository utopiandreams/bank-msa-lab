package lab.bank.bankmock.api

import lab.bank.bankmock.TestcontainersConfiguration
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

// HTTP 계약: 거절도 200 (판정이 확실하므로), 4xx는 판정 못 한 요청에만
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class InterbankControllerTest {

    @Autowired lateinit var mockMvc: MockMvc

    private fun send(key: String?, body: String) = mockMvc.post("/interbank/transfers") {
        key?.let { header("Idempotency-Key", it) }
        contentType = MediaType.APPLICATION_JSON
        content = body
    }

    @Test
    fun `성공은 200 ACCEPTED`() {
        send("c-1", """{"toBankCode":"088","toAccountNumber":"1234567890","amount":10000}""").andExpect {
            status { isOk() }
            jsonPath("$.idempotencyKey") { value("c-1") }
            jsonPath("$.outcome") { value("ACCEPTED") }
        }
    }

    @Test
    fun `거절도 200 - REJECTED와 사유`() {
        send("c-2", """{"toBankCode":"088","toAccountNumber":"9990001","amount":10000}""").andExpect {
            status { isOk() }
            jsonPath("$.outcome") { value("REJECTED") }
            jsonPath("$.rejectReason") { value("ACCOUNT_NOT_FOUND") }
        }
    }

    @Test
    fun `같은 키에 다른 금액이면 409`() {
        send("c-3", """{"toBankCode":"088","toAccountNumber":"1234567890","amount":10000}""")
        send("c-3", """{"toBankCode":"088","toAccountNumber":"1234567890","amount":99999}""").andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("IDEMPOTENCY_CONFLICT") }
        }
    }

    @Test
    fun `조회 - 보낸 것은 판정 그대로, 안 보낸 것은 200 NOT_FOUND`() {
        send("c-5", """{"toBankCode":"088","toAccountNumber":"9990001","amount":10000}""")
        mockMvc.get("/interbank/transfers/c-5").andExpect {
            status { isOk() }
            jsonPath("$.status") { value("REJECTED") }
            jsonPath("$.rejectReason") { value("ACCOUNT_NOT_FOUND") }
        }
        mockMvc.get("/interbank/transfers/never-sent").andExpect {
            status { isOk() }
            jsonPath("$.status") { value("NOT_FOUND") }
        }
    }

    @Test
    fun `멱등 키 없거나 금액이 0이면 400`() {
        send(null, """{"toBankCode":"088","toAccountNumber":"1234567890","amount":10000}""")
            .andExpect { status { isBadRequest() } }
        send("c-4", """{"toBankCode":"088","toAccountNumber":"1234567890","amount":0}""")
            .andExpect { status { isBadRequest() } }
    }
}
