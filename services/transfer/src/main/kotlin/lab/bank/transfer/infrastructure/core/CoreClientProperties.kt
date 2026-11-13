package lab.bank.transfer.infrastructure.core

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * core 호출 설정 (application.yaml의 core.*). 하드코딩하지 않는 이유: 15주 차에 타임아웃을 바꿔 가며 장애 전파를 실험한다.
 *
 * connectTimeout: TCP 연결을 맺는 데 기다리는 시간. 넘으면 요청이 core에 **도달하지 않은** 것
 * readTimeout:    요청을 보낸 뒤 응답을 기다리는 시간. 넘으면 core가 **처리했는지 모른다** (UNKNOWN의 주된 원인)
 */
@ConfigurationProperties(prefix = "core")
data class CoreClientProperties(
    val baseUrl: String,
    val connectTimeout: Duration,
    val readTimeout: Duration,
)
