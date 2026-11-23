package lab.bank.transfer.infrastructure.tracing

import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.context.Context
import io.opentelemetry.context.propagation.TextMapGetter
import io.opentelemetry.context.propagation.TextMapSetter

/**
 * trace 문맥 ↔ W3C `traceparent` 문자열 (9주 차, Outbox에서 끊긴 트레이스 잇기).
 *
 * HTTP·Kafka는 에이전트가 헤더로 이 문자열을 자동으로 넘긴다. DB 행은 에이전트가 모르는 길이라 우리가 직접 적고 꺼낸다.
 * 형식: `00-<trace ID 32자>-<부모 span ID 16자>-<플래그 2자>`. trace ID만 적으면 트리 어디에 붙을지(부모) 모르고,
 * 플래그(01 = 기록함)가 없으면 "이 요청을 기록하기로 했나"라는 샘플링 결정을 이어받지 못한다.
 *
 * OTel API(벤더 중립)만 쓴다: 에이전트가 이 API 호출을 자기 쪽으로 이어 주고, 에이전트가 없으면(테스트) 아무것도 안 한다.
 * 저장소를 Datadog 등으로 바꿔도 이 코드는 그대로다 (observability.md 5-0).
 */
object TraceParents {

    private const val HEADER = "traceparent"
    private val propagator = W3CTraceContextPropagator.getInstance()

    /** 지금 스레드의 trace 문맥. 진행 중인 trace가 없으면 null */
    fun current(): String? {
        val carrier = mutableMapOf<String, String>()
        propagator.inject(Context.current(), carrier, setter)
        return carrier[HEADER]
    }

    /** 저장해 둔 문자열 → 부모로 쓸 문맥. null이거나 형식이 틀리면 부모 없는 문맥(새 trace) */
    fun toContext(traceParent: String?): Context {
        if (traceParent == null) return Context.root()
        return propagator.extract(Context.root(), mapOf(HEADER to traceParent), getter)
    }

    private val setter = TextMapSetter<MutableMap<String, String>> { carrier, key, value -> carrier?.put(key, value) }

    private val getter = object : TextMapGetter<Map<String, String>> {
        override fun keys(carrier: Map<String, String>): Iterable<String> = carrier.keys
        override fun get(carrier: Map<String, String>?, key: String): String? = carrier?.get(key)
    }
}
