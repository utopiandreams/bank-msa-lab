package lab.bank.transfer.application.port.out

import java.util.UUID

/**
 * 외부 결제망(금융결제원 + 타행)에 이체를 보내는 포트. 지금 구현은 bank-mock (infrastructure/bankmock).
 * 포트 이름이 bank-mock이 아닌 이유: 유스케이스는 "외부 은행"만 안다. 진짜 결제망으로 바꿔도 어댑터만 갈아 끼운다
 * 예외를 던지지 않는다 (CorePostingPort와 같은 이유: 결과 불명이 catch 누락으로 조용히 사라지지 않게)
 */
interface ExternalBankPort {

    fun send(command: ExternalTransferCommand): ExternalTransferResult

    /**
     * 외부 계약: GET /interbank/transfers/{송금 ID}. 돈을 움직이지 않는 조회라 몇 번을 불러도 안전하다. 예외를 던지지 않는다.
     * **외부 처리 상한이 지난 뒤에만 부른다** (그 전의 NotFound는 "아직 처리 중"일 수 있다)
     */
    fun lookup(transferId: UUID): ExternalTransferLookupResult
}
