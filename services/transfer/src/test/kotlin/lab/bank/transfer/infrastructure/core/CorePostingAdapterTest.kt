package lab.bank.transfer.infrastructure.core

import lab.bank.transfer.application.port.out.CoreTransferResult
import lab.bank.transfer.domain.Amount
import lab.bank.transfer.domain.IdempotencyKey
import lab.bank.transfer.domain.Transfer
import lab.bank.transfer.domain.UserId
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.measureTimedValue
import com.sun.net.httpserver.HttpServer

/**
 * core 어댑터 테스트. Spring 컨텍스트 없이 어댑터만 만들어 빠르게 돌린다.
 *
 * - 응답 코드별 분류: MockRestServiceServer (Spring 내장, 네트워크 없이 RestClient의 응답을 가짜로 꽂는다)
 * - 타임아웃·연결 실패: 가짜 응답으로는 재현이 안 된다 (MockRestServiceServer는 소켓을 안 쓴다)
 *   → JDK 내장 HttpServer로 진짜 소켓을 열고, 실제 설정 클래스(CoreClientConfig)로 만든 클라이언트를 붙인다
 */
class CorePostingAdapterTest {

    private val transfer = Transfer.request(
        requesterId = UserId("chulsoo"),
        idempotencyKey = IdempotencyKey("adapter-test"),
        fromAccountId = UUID.fromString("00000000-0000-0000-0000-00000000000a"),
        toAccountId = UUID.fromString("00000000-0000-0000-0000-00000000000b"),
        amount = Amount(10_000),
    )

    @Nested
    inner class `응답 코드별 분류` {

        private val builder = RestClient.builder().baseUrl("http://core")
        private val server = MockRestServiceServer.bindTo(builder).build()
        private val adapter = CorePostingAdapter(builder.build())

        // core 응답을 흉내 낸다. 에러 body는 core의 PostingExceptionHandler가 만드는 ProblemDetail 형식
        private fun respond(status: HttpStatus, body: String = "", contentType: MediaType = MediaType.APPLICATION_JSON) {
            server.expect(requestTo("http://core/postings/transfers"))
                .andRespond(withStatus(status).contentType(contentType).body(body))
        }

        private fun problem(status: HttpStatus, code: String) {
            respond(
                status,
                """{"type":"about:blank","title":"${status.reasonPhrase}","status":${status.value()},"detail":"테스트","code":"$code"}""",
                MediaType.APPLICATION_PROBLEM_JSON,
            )
        }

        @Test
        fun `요청 형식 - 송금 ID를 멱등 키 헤더로, 보내는·받는 계좌를 뒤집지 않고 보낸다`() {
            server.expect(requestTo("http://core/postings/transfers"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Idempotency-Key", transfer.id.toString()))
                .andExpect(jsonPath("$.fromAccountId").value(transfer.fromAccountId.toString()))
                .andExpect(jsonPath("$.toAccountId").value(transfer.toAccountId.toString()))
                .andExpect(jsonPath("$.amount").value(10_000))
                .andRespond(withSuccess("""{"entryId":"${UUID.randomUUID()}","replayed":false}""", MediaType.APPLICATION_JSON))

            adapter.transfer(transfer)

            server.verify()
        }

        @Test
        fun `201 처음 처리 - Completed, 원장 ID를 그대로 담는다`() {
            val entryId = UUID.randomUUID()
            respond(HttpStatus.CREATED, """{"entryId":"$entryId","replayed":false}""")

            assertEquals(CoreTransferResult.Completed(entryId), adapter.transfer(transfer))
        }

        @Test
        fun `200 같은 키 재요청 - Completed`() {
            val entryId = UUID.randomUUID()
            respond(HttpStatus.OK, """{"entryId":"$entryId","replayed":true}""")

            assertEquals(CoreTransferResult.Completed(entryId), adapter.transfer(transfer))
        }

        @Test
        fun `2xx인데 body를 못 읽으면 - FAILED가 아니라 Unknown (커밋은 됐을 수 있다)`() {
            respond(HttpStatus.CREATED, """{"unexpected":"format"}""")

            assertIs<CoreTransferResult.Unknown>(adapter.transfer(transfer))
        }

        @Test
        fun `2xx인데 body가 비어 있어도 - Unknown`() {
            respond(HttpStatus.CREATED, "")

            assertIs<CoreTransferResult.Unknown>(adapter.transfer(transfer))
        }

        @Test
        fun `422 잔액 부족 - Rejected, core의 에러 코드를 그대로`() {
            problem(HttpStatus.UNPROCESSABLE_CONTENT, "INSUFFICIENT_BALANCE")

            assertEquals(CoreTransferResult.Rejected("INSUFFICIENT_BALANCE"), adapter.transfer(transfer))
        }

        @Test
        fun `404 계좌 없음 - Rejected`() {
            problem(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND")

            assertEquals(CoreTransferResult.Rejected("ACCOUNT_NOT_FOUND"), adapter.transfer(transfer))
        }

        @Test
        fun `400 입력 오류 - Rejected`() {
            problem(HttpStatus.BAD_REQUEST, "INVALID_AMOUNT")

            assertEquals(CoreTransferResult.Rejected("INVALID_AMOUNT"), adapter.transfer(transfer))
        }

        @Test
        fun `거절인데 에러 body가 없으면 - 상태 코드로 대체 코드를 만든다`() {
            respond(HttpStatus.UNPROCESSABLE_CONTENT, "")

            assertEquals(CoreTransferResult.Rejected("CORE_HTTP_422"), adapter.transfer(transfer))
        }

        @Test
        fun `409 멱등 충돌 - Rejected가 아니라 Unknown (같은 송금 ID로 다른 거래가 이미 있다)`() {
            problem(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT")

            assertIs<CoreTransferResult.Unknown>(adapter.transfer(transfer))
        }

        @Test
        fun `500 - Unknown (커밋 직후 터졌을 수 있다)`() {
            respond(HttpStatus.INTERNAL_SERVER_ERROR, "")

            assertIs<CoreTransferResult.Unknown>(adapter.transfer(transfer))
        }

        @Test
        fun `503 - Unknown`() {
            respond(HttpStatus.SERVICE_UNAVAILABLE, "")

            assertIs<CoreTransferResult.Unknown>(adapter.transfer(transfer))
        }
    }

    @Nested
    inner class `응답을 못 받는 경우 - 진짜 소켓` {

        // 실제 설정 클래스로 클라이언트를 만든다 → 설정의 타임아웃이 정말 걸리는지까지 검증
        private fun adapterFor(baseUrl: String, readTimeout: Duration) = CorePostingAdapter(
            CoreClientConfig().coreRestClient(
                CoreClientProperties(baseUrl = baseUrl, connectTimeout = Duration.ofMillis(500), readTimeout = readTimeout),
            ),
        )

        @Test
        fun `read 타임아웃 - core가 늦게 답하면 기다리지 않고 Unknown`() {
            // core가 처리는 하는데 응답이 늦는 상황 (DB 락 대기, GC 등)
            // 고정 시간 sleep 대신 래치로 붙잡아 둔다: 테스트가 끝나면 바로 풀어 줘서 서버 종료를 기다리지 않게
            val release = CountDownLatch(1)
            val slowCore = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/postings/transfers") { exchange ->
                    try {
                        release.await(5, TimeUnit.SECONDS)
                        exchange.sendResponseHeaders(201, -1)
                    } catch (_: Exception) {
                        // 서버를 먼저 닫으면 여기로 온다. 테스트 결과와 무관
                    } finally {
                        exchange.close()
                    }
                }
                start()
            }
            try {
                val adapter = adapterFor("http://127.0.0.1:${slowCore.address.port}", readTimeout = Duration.ofMillis(200))

                val (result, elapsed) = measureTimedValue { adapter.transfer(transfer) }

                assertIs<CoreTransferResult.Unknown>(result)
                // 서버는 아직 응답하지 않았는데 돌아왔다 = 타임아웃이 실제로 걸렸다
                assert(elapsed.inWholeMilliseconds < 1_500) { "타임아웃이 안 걸린 것 같다: ${elapsed.inWholeMilliseconds}ms" }
            } finally {
                release.countDown()
                slowCore.stop(0)
            }
        }

        @Test
        fun `연결 실패 - core가 떠 있지 않으면 Unknown`() {
            // 빈 포트를 하나 잡았다가 닫는다 → 그 포트엔 아무도 듣고 있지 않다 (연결 거부)
            val deadPort = ServerSocket(0).use { it.localPort }
            val adapter = adapterFor("http://127.0.0.1:$deadPort", readTimeout = Duration.ofSeconds(1))

            assertIs<CoreTransferResult.Unknown>(adapter.transfer(transfer))
        }
    }
}
