package lab.bank.transfer.application.port.out

import java.time.Instant

/** 대사 추출 대상 송금을 읽는 포트 */
interface TransferExtractSource {

    /**
     * [from, to) 사이에 **만들어졌거나 상태가 바뀐** 송금 전부 (당행·타행)를 한 줄씩 넘긴다.
     * 리스트로 돌려주지 않는 이유: 100만 건이면 메모리에 다 못 올린다 → DB 커서로 조금씩 읽어 바로 넘긴다 (학습 노트 reconciliation 8절).
     * 만든 날만 보면 안 되는 이유: 그날 끝나지 않은 송금(결과 불명·보상 중)은 대사가 이월하는데, 다음 날 끝 상태가 됐을 때
     * 새 상태가 어느 파일에도 안 나오면 대사가 영영 판정을 못 한다 → 상태가 바뀐 날의 파일에도 실어 보낸다 (설계 연습 09 결정 21)
     */
    fun forEachChangedBetween(from: Instant, to: Instant, action: (TransferExtractRow) -> Unit)
}
