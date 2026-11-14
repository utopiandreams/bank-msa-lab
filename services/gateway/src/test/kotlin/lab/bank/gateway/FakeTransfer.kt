package lab.bank.gateway

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

/**
 * transfer 자리에 두는 가짜 서버 (JDK 내장 HttpServer, 진짜 소켓).
 * 받은 요청을 기록해 두고 201 "transfer-fake"로 답한다 → 테스트는 "무엇이 전달됐나"를 검사한다.
 * 테스트 클래스 여러 개가 같은 JVM에서 공유한다 (Spring 컨텍스트 캐시와 수명을 맞춤).
 */
object FakeTransfer {

    data class Received(
        val method: String,
        val path: String,
        val headers: Map<String, List<String>>,
        val body: String,
    ) {
        fun header(name: String): List<String> =
            headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value ?: emptyList()
    }

    val received = CopyOnWriteArrayList<Received>()

    private val server: HttpServer = HttpServer.create(InetSocketAddress("localhost", 0), 0).apply {
        createContext("/") { ex ->
            received += Received(
                method = ex.requestMethod,
                path = ex.requestURI.toString(),
                headers = ex.requestHeaders.toMap(),
                body = ex.requestBody.readAllBytes().decodeToString(),
            )
            val bytes = "transfer-fake".toByteArray()
            ex.sendResponseHeaders(201, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        start()
    }

    val baseUrl: String get() = "http://localhost:${server.address.port}"
}
