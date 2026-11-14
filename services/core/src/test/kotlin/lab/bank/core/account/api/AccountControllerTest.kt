package lab.bank.core.account.api

import com.jayway.jsonpath.JsonPath
import lab.bank.core.TestcontainersConfiguration
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.util.UUID
import kotlin.test.assertEquals

// 계좌 API: 예금주는 X-User-Id로 정하고, 조회는 주인만 (G-4, ADR-0002)
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class AccountControllerTest {

    @Autowired lateinit var mockMvc: MockMvc

    private fun open(user: String): String {
        val body = mockMvc.post("/accounts") { header("X-User-Id", user) }
            .andExpect { status { isCreated() } }
            .andReturn().response.contentAsString
        return JsonPath.read(body, "$.accountId")
    }

    @Test
    fun `계좌 개설 - 예금주는 바디가 아니라 X-User-Id`() {
        mockMvc.post("/accounts") { header("X-User-Id", "chulsoo") }.andExpect {
            status { isCreated() }
            jsonPath("$.ownerId") { value("chulsoo") }
            jsonPath("$.balance") { value(0) }
        }
    }

    @Test
    fun `X-User-Id 없이 개설하면 401 MISSING_USER`() {
        mockMvc.post("/accounts").andExpect {
            status { isUnauthorized() }
            jsonPath("$.code") { value("MISSING_USER") }
        }
    }

    @Test
    fun `내 계좌는 조회된다`() {
        val id = open("chulsoo")

        mockMvc.get("/accounts/$id") { header("X-User-Id", "chulsoo") }.andExpect {
            status { isOk() }
            jsonPath("$.accountId") { value(id) }
        }
    }

    // BOLA: 남의 계좌 ID를 넣으면 잔액이 보이면 안 된다. 그리고 "없는 계좌"와 응답이 같아야 존재 여부가 안 샌다
    @Test
    fun `남의 계좌 조회는 없는 계좌와 똑같은 404`() {
        val chulsoos = open("chulsoo")

        val others = mockMvc.get("/accounts/$chulsoos") { header("X-User-Id", "younghee") }
            .andExpect { status { isNotFound() } }.andReturn().response.contentAsString
        val missing = mockMvc.get("/accounts/${UUID.randomUUID()}") { header("X-User-Id", "younghee") }
            .andExpect { status { isNotFound() } }.andReturn().response.contentAsString

        assertEquals(JsonPath.read<String>(missing, "$.code"), JsonPath.read<String>(others, "$.code"))
        assertEquals("ACCOUNT_NOT_FOUND", JsonPath.read<String>(others, "$.code"))
    }
}
