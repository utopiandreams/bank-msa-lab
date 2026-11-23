package lab.bank.transfer.application

/**
 * 멈춘 당행 송금 해소 (인바운드 포트, 8주 차 ADR-0005).
 *
 * 부르는 쪽: infrastructure/resolution/UnknownResolutionScheduler (스케줄러 = 시간이 부르는 인바운드 어댑터. 컨트롤러가 HTTP가 부르는 어댑터인 것과 같다)
 * 고객이 없어도 PROCESSING·UNKNOWN에 멈춘 송금을 core **조회**로 끝 상태로 만든다. 재전송은 하지 않는다 (pitfalls 38).
 */
interface TransferResolutionUseCase {

    /** 한 회차: 보낸 지 기준 시간이 지난 멈춘 송금을 몇 건 잡아 조회로 확정한다. 확정한(끝 상태가 된) 건수를 돌려준다 */
    fun resolveStuck(): Int
}
