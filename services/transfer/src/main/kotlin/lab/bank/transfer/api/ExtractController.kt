package lab.bank.transfer.api

import lab.bank.transfer.application.DailyExtractUseCase
import lab.bank.transfer.application.ExtractResult
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate

/**
 * 대사 추출을 손으로 다시 돌리는 내부 API (운영자·시연용). gateway 라우트(/transfers 아래)에 없어 밖에서 닿지 않는다.
 * 예: kubectl -n bank exec deploy/transfer -- curl -s -XPOST localhost:8080/internal/extracts/2026-11-25
 */
@RestController
class ExtractController(private val extractUseCase: DailyExtractUseCase) {

    @PostMapping("/internal/extracts/{businessDate}")
    fun extract(@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) businessDate: LocalDate): ExtractResult =
        extractUseCase.extract(businessDate)
}
