package lab.bank.transfer.api

import lab.bank.transfer.application.TransferQuery
import lab.bank.transfer.application.TransferUseCase
import lab.bank.transfer.domain.Amount
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.util.UriComponentsBuilder
import java.util.UUID

/**
 * 송금 API.
 *
 * POST 응답 코드 (임시 결정, T-1 설계 질문 3번에서 확정):
 *   결과와 상관없이 201 + Location. 송금 기록(자원)은 COMPLETED든 FAILED든 UNKNOWN이든 만들어졌으므로.
 *   결과는 body의 status로 구분한다. UNKNOWN이면 클라이언트는 Location(GET)으로 나중에 다시 확인한다.
 */
@RestController
@RequestMapping("/transfers")
class TransferController(
    private val transferUseCase: TransferUseCase,
    private val transferQuery: TransferQuery,
) {

    @PostMapping
    fun create(@RequestBody request: CreateTransferRequest): ResponseEntity<TransferResponse> {
        // 원시 값 → 값 객체 변환이 곧 검증. 실패하면 InvalidAmountException → 400
        val transfer = transferUseCase.transfer(request.fromAccountId, request.toAccountId, Amount(request.amount))
        val location = UriComponentsBuilder.fromPath("/transfers/{id}").buildAndExpand(transfer.id).toUri()
        return ResponseEntity.created(location).body(TransferResponse.from(transfer))
    }

    // 송금은 우리가 발급한 ID로 찾는 자원이라 없으면 404 (core 상태 조회는 "처리 안 됨"이 정상 답이라 200이었던 것과 다르다)
    @GetMapping("/{transferId}")
    fun get(@PathVariable transferId: UUID): ResponseEntity<TransferResponse> =
        transferQuery.find(transferId)
            ?.let { ResponseEntity.ok(TransferResponse.from(it)) }
            ?: ResponseEntity.notFound().build()
}
