package lab.bank.core.extract.api

import lab.bank.common.event.ExtractCompletedEvent
import lab.bank.core.extract.application.CoreDailyExtractService
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate

/**
 * 대사 추출을 손으로 다시 돌리는 내부 API (운영자·시연용). core는 Ingress가 없고 gateway도 라우팅하지 않아 밖에서 닿지 않는다.
 * 예: kubectl -n bank exec deploy/core -- curl -s -XPOST localhost:8080/internal/extracts/2026-11-25
 */
@RestController
class ExtractController(private val extractService: CoreDailyExtractService) {

    @PostMapping("/internal/extracts/{businessDate}")
    fun extract(@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) businessDate: LocalDate): ExtractCompletedEvent =
        extractService.extract(businessDate)
}
