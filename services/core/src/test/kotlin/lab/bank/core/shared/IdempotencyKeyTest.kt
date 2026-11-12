package lab.bank.core.shared

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class IdempotencyKeyTest {

    @Test
    fun `64자까지는 만들어진다`() {
        assertEquals(64, IdempotencyKey("k".repeat(64)).value.length)   // 경계값 = DB 컬럼 길이
    }

    @Test
    fun `65자는 만들 수 없다`() {
        // 막지 않으면 DB varchar(64)에서 터져 중복 키 재시도에 잘못 잡히고 500이 된다
        assertFailsWith<InvalidIdempotencyKeyException> { IdempotencyKey("k".repeat(65)) }
    }

    @Test
    fun `비어 있거나 공백뿐이면 만들 수 없다`() {
        assertFailsWith<InvalidIdempotencyKeyException> { IdempotencyKey("") }
        assertFailsWith<InvalidIdempotencyKeyException> { IdempotencyKey("   ") }
    }
}
