package lab.bank.transfer.infrastructure.persistence

import lab.bank.common.event.TransferFinishedEvent
import lab.bank.transfer.FakeCoreConfiguration
import lab.bank.transfer.TestcontainersConfiguration
import lab.bank.transfer.domain.Amount
import lab.bank.transfer.domain.BankCode
import lab.bank.transfer.domain.IdempotencyKey
import lab.bank.transfer.domain.InterbankTransfer
import lab.bank.transfer.domain.Transfer
import lab.bank.transfer.domain.UserId
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import tools.jackson.databind.json.JsonMapper
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Outbox 발행기: outbox_event에 무엇이 적히나 (이벤트 JSON 계약, eventId = 행 ID, 키 = 송금 ID).
 * 이전 KafkaTransferEventPublisherTest의 JSON 검사를 옮겨 왔다. 릴레이는 끄고 테이블만 본다.
 *
 * 트랜잭션 안에서 불리는 건 유스케이스의 몫이라 여기선 따로 열지 않는다 (save가 자기 트랜잭션으로 커밋).
 */
@SpringBootTest(properties = ["outbox.relay.enabled=false"])
@Import(TestcontainersConfiguration::class, FakeCoreConfiguration::class)
class OutboxTransferEventPublisherTest {

    @Autowired lateinit var publisher: OutboxTransferEventPublisher   // 포트는 가짜(@Primary)라 구현 타입으로 직접 받는다
    @Autowired lateinit var repository: OutboxJpaRepository
    @Autowired lateinit var jsonMapper: JsonMapper

    private fun rowOf(transferId: UUID): OutboxEventJpaEntity =
        repository.findAll().single { it.aggregateId == transferId }

    @Test
    fun `당행 FAILED - 토픽·키·eventId와 이벤트 JSON이 적힌다, 아직 미발행`() {
        val transfer = Transfer.request(UserId("chulsoo"), IdempotencyKey("k-${UUID.randomUUID()}"), UUID.randomUUID(), UUID.randomUUID(), Amount(10_000))
            .apply {
                startProcessing()
                failedProcessing("INSUFFICIENT_BALANCE")
            }

        publisher.publishFinished(transfer)

        val row = rowOf(transfer.id)
        assertEquals(TransferFinishedEvent.TOPIC, row.topic)
        assertEquals(transfer.id.toString(), row.messageKey)    // 파티션 키 = 송금 ID (ADR-0003)
        assertNull(row.publishedAt)
        val json = jsonMapper.readTree(row.payload)
        assertEquals(row.id.toString(), json["eventId"].asString())   // 행 ID = eventId (재시도해도 같은 값, pitfalls 29)
        assertEquals(transfer.id.toString(), json["transferId"].asString())
        assertEquals("chulsoo", json["requesterId"].asString())
        assertEquals(10_000, json["amount"].asLong())
        assertEquals("FAILED", json["status"].asString())
        assertEquals("INSUFFICIENT_BALANCE", json["failureCode"].asString())
        assertEquals(TransferFinishedEvent.SCHEMA_VERSION, json["schemaVersion"].asInt())
    }

    @Test
    fun `타행 CANCELLED - 받는 쪽은 은행 코드·계좌번호, failureCode는 외부 거절 사유`() {
        val transfer = InterbankTransfer.request(UserId("chulsoo"), IdempotencyKey("k-${UUID.randomUUID()}"), UUID.randomUUID(), BankCode("004"), "123-456", Amount(10_000))
            .apply {
                withdrawRequested()
                withdrawn(UUID.randomUUID())
                externalBankRequested()
                externalBankRejected("ACCOUNT_NOT_FOUND")
                cancelled(UUID.randomUUID())
            }

        publisher.publishFinished(transfer)

        val json = jsonMapper.readTree(rowOf(transfer.id).payload)
        assertEquals("CANCELLED", json["status"].asString())
        assertEquals("ACCOUNT_NOT_FOUND", json["failureCode"].asString())
        assertEquals("004", json["toBankCode"].asString())
        assertEquals("123-456", json["toAccountNumber"].asString())
        assertTrue(json["toAccountId"].isNull)
    }
}
