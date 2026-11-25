package lab.bank.reconciliation.application

import java.time.LocalDate

/** 외부 기관의 정산 파일 제공 채널 (구현: bank-mock HTTP). 실제로는 전용선·SFTP·기관 API (설계 연습 09 결정 19) */
interface ExternalSettlementClient {
    fun fetch(date: LocalDate): ByteArray
}
