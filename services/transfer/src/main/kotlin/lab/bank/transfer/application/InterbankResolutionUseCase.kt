package lab.bank.transfer.application

/**
 * 멈춘 타행 송금 해소 (인바운드 포트, 8주 차 2부, 설계 연습 08 2부).
 *
 * 부르는 쪽: infrastructure/resolution/InterbankResolutionScheduler.
 * 사가 중간 상태(WITHDRAW_REQUESTED·WITHDRAWN·EXTERNAL_BANK_REQUESTED·WITHDRAW_CANCEL_REQUESTED)에 멈춘 송금을
 * 출금·외부는 **조회**로 확정하고, 보상(출금 취소)만 성공할 때까지 재시도한다. 재전송은 하지 않는다.
 * 당행(TransferResolutionUseCase)과 나눈 이유: 상태·포트·기준 시각이 다르고, 트랜잭션 단위도 다르다 (결정 7)
 */
interface InterbankResolutionUseCase {

    /** 한 회차: 멈춘 타행 송금을 한 건씩 잡아 해소한다 (최대 batchSize건). 끝 상태가 된 건수를 돌려준다 */
    fun resolveStuck(): Int
}
