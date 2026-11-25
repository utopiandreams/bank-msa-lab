package lab.bank.reconciliation.api

import lab.bank.reconciliation.application.ExternalFileReceiver
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate

/**
 * 운영자·시연용 내부 API (Ingress 없음, 클러스터 안에서만). 외부 정산 파일을 손으로 다시 받는다.
 * 예: kubectl -n bank port-forward svc/reconciliation 8080:80 → curl -XPOST localhost:8080/internal/external-files/2026-11-25
 */
@RestController
class InternalController(private val receiver: ExternalFileReceiver) {

    @PostMapping("/internal/external-files/{date}")
    fun receive(@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) date: LocalDate): Map<String, Any> {
        val a = receiver.receive(date)
        return mapOf("extractId" to a.extractId, "objectKey" to a.objectKey, "rowCount" to a.rowCount, "amountSum" to a.amountSum)
    }
}
