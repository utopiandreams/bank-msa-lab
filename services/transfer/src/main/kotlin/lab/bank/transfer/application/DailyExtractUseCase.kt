package lab.bank.transfer.application

import java.time.LocalDate

/**
 * 일자별 대사 추출 (인바운드 포트, ADR-0006). 부르는 쪽: 매일 새벽 스케줄러(전날 분), 다시 추출할 때 내부 API.
 * 영업일 하루치 송금을 CSV로 저장소에 올리고 → 다 올린 뒤 완료 이벤트를 발행한다.
 * 같은 날짜를 여러 번 불러도 된다: 매번 새 파일(새 추출 ID)이 생기고, 대사 서비스는 가장 마지막 것을 쓴다
 */
interface DailyExtractUseCase {
    fun extract(businessDate: LocalDate): ExtractResult
}
