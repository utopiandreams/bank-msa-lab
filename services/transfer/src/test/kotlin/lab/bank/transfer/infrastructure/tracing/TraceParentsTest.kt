package lab.bank.transfer.infrastructure.tracing

import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanContext
import io.opentelemetry.api.trace.TraceFlags
import io.opentelemetry.api.trace.TraceState
import io.opentelemetry.context.Context
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * 에이전트 없이 OTel API만으로 확인한다: 진행 중인 구간을 손으로 만들어 현재 문맥에 올려 둔다.
 * (실제로 트레이스가 이어지는지는 클러스터의 Tempo에서 본다)
 */
class TraceParentsTest {

    private val traceId = "4bf92f3577b34da6a3ce929d0e0e4736"
    private val spanId = "00f067aa0ba902b7"

    @Test
    fun `진행 중인 구간이 있으면 traceparent 문자열로 적는다`() {
        val span = Span.wrap(SpanContext.create(traceId, spanId, TraceFlags.getSampled(), TraceState.getDefault()))

        val traceParent = Context.root().with(span).makeCurrent().use { TraceParents.current() }

        assertEquals("00-$traceId-$spanId-01", traceParent)
    }

    @Test
    fun `진행 중인 trace가 없으면 null`() {
        assertNull(TraceParents.current())
    }

    @Test
    fun `적어 둔 문자열을 되살리면 같은 trace의 같은 부모를 가리킨다`() {
        val parent = Span.fromContext(TraceParents.toContext("00-$traceId-$spanId-01")).spanContext

        assertEquals(traceId, parent.traceId)
        assertEquals(spanId, parent.spanId)
        assertEquals(true, parent.isSampled)
        assertEquals(true, parent.isRemote)   // 다른 곳(요청 스레드)에서 온 문맥
    }

    @Test
    fun `null이나 깨진 문자열은 부모 없음(새 trace)`() {
        assertFalse(Span.fromContext(TraceParents.toContext(null)).spanContext.isValid)
        assertFalse(Span.fromContext(TraceParents.toContext("garbage")).spanContext.isValid)
    }
}
