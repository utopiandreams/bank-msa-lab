package lab.bank.transfer.infrastructure.persistence

import lab.bank.transfer.FakeCoreConfiguration
import lab.bank.transfer.TestcontainersConfiguration
import lab.bank.transfer.application.port.out.InterbankTransferRepository
import lab.bank.transfer.domain.Amount
import lab.bank.transfer.domain.BankCode
import lab.bank.transfer.domain.IdempotencyKey
import lab.bank.transfer.domain.InterbankTransfer
import lab.bank.transfer.domain.InterbankTransferStatus
import lab.bank.transfer.domain.InterbankTransferStatus.CANCELLED
import lab.bank.transfer.domain.InterbankTransferStatus.COMPLETED
import lab.bank.transfer.domain.InterbankTransferStatus.EXTERNAL_BANK_REQUESTED
import lab.bank.transfer.domain.InterbankTransferStatus.FAILED
import lab.bank.transfer.domain.InterbankTransferStatus.WITHDRAWN
import lab.bank.transfer.domain.InterbankTransferStatus.WITHDRAW_CANCEL_REQUESTED
import lab.bank.transfer.domain.InterbankTransferStatus.WITHDRAW_REQUESTED
import lab.bank.transfer.domain.UserId
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals

/**
 * 타행 해소 대상 조회 (설계 연습 08 2부 결정 2): 홉마다 기준 시각이 다르다.
 * core 홉(출금·출금 취소, WITHDRAWN 포함)은 coreSentBefore, 외부 홉(EXTERNAL_BANK_REQUESTED)은 externalSentBefore.
 * 당행 조회 테스트(TransferUnresolvedQueryTest)처럼 이 테스트가 만든 행만 본다 (DB를 테스트끼리 공유).
 * SKIP LOCKED 동작은 당행 테스트가 같은 SQL 절로 증명한다
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class, FakeCoreConfiguration::class)
class InterbankStuckQueryTest {

    @Autowired lateinit var repository: InterbankTransferRepository
    @Autowired lateinit var transactionTemplate: TransactionTemplate

    // 상태에 맞는 칸을 채운다 (출금 뒤엔 출금 전표, FAILED는 사유, CANCELLED는 취소 전표)
    private fun at(updatedAt: Instant, status: InterbankTransferStatus): InterbankTransfer =
        repository.save(
            InterbankTransfer(
                id = UUID.randomUUID(),
                requesterId = UserId("chulsoo"),
                idempotencyKey = IdempotencyKey("sq-${UUID.randomUUID()}"),
                fromAccountId = UUID.randomUUID(),
                toAccountId = "110-1234",
                toBankCode = BankCode("004"),
                amount = Amount(30_000),
                status = status,
                failureCode = if (status == FAILED) "TEST" else null,
                coreEntryId = if (status != WITHDRAW_REQUESTED && status != FAILED) UUID.randomUUID() else null,
                cancelEntryId = if (status == CANCELLED) UUID.randomUUID() else null,
                externalRejectReason = null,
                createdAt = updatedAt,
                updatedAt = updatedAt,
                version = null,
            ),
        )

    private fun find(coreSentBefore: Instant, externalSentBefore: Instant, limit: Int = 100) =
        transactionTemplate.execute { repository.findStuckForUpdate(coreSentBefore, externalSentBefore, limit) }!!

    @Test
    fun `중간 상태만, 홉마다 다른 기준 시각으로, 오래된 순`() {
        val day = Instant.parse("2002-01-01T00:00:00Z")
        val coreCutoff = day.plusSeconds(10)
        val externalCutoff = day.plusSeconds(5)   // 외부 홉은 기준이 더 길다 = 기준 시각이 더 이르다

        val withdrawRequested = at(day.plusSeconds(1), WITHDRAW_REQUESTED)
        val withdrawn = at(day.plusSeconds(2), WITHDRAWN)
        val cancelRequested = at(day.plusSeconds(3), WITHDRAW_CANCEL_REQUESTED)
        val externalOld = at(day.plusSeconds(4), EXTERNAL_BANK_REQUESTED)
        val externalYoung = at(day.plusSeconds(7), EXTERNAL_BANK_REQUESTED)   // core 기준은 지났지만 외부 처리 상한 전: 아직 묻지 않는다
        val coreYoung = at(day.plusSeconds(20), WITHDRAW_REQUESTED)           // core 기준 전
        val finals = listOf(COMPLETED, FAILED, CANCELLED).map { at(day.plusSeconds(1), it) }
        val mine = (listOf(withdrawRequested, withdrawn, cancelRequested, externalOld, externalYoung, coreYoung) + finals).map { it.id }.toSet()

        val found = find(coreCutoff, externalCutoff).map { it.id }.filter { it in mine }

        assertEquals(listOf(withdrawRequested.id, withdrawn.id, cancelRequested.id, externalOld.id), found)
    }

    @Test
    fun `limit만큼만 잡는다`() {
        val day = Instant.parse("2002-02-01T00:00:00Z")
        repeat(3) { at(day.plusSeconds(it.toLong()), WITHDRAWN) }
        assertEquals(2, find(day.plusSeconds(10), day.plusSeconds(10), limit = 2).size)
    }
}
