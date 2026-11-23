package lab.bank.core.account.application.balance

import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicReference

/**
 * 지금 쓰는 락 전략 (실행 중에 바꿀 수 있는 실험 스위치). 바꾸는 입구는 관리 포트의 ExperimentsEndpoint
 *
 * AtomicReference인 이유: 요청 스레드 수백 개가 읽고, 관리 API 스레드 하나가 쓴다.
 * 그냥 var면 한 스레드가 바꾼 값을 다른 스레드가 **언제 볼지 보장이 없다** (CPU 캐시·재정렬, 자바 메모리 모델의 가시성).
 * volatile로도 되지만, 바꾸면서 이전 값을 돌려주는 getAndSet이 한 번에 되는 AtomicReference가 편하다
 *
 * 한계 (알고 쓴다):
 * - **파드 메모리에만** 있다 → replicas가 2 이상이면 파드마다 바꿔야 하고, 재시작하면 LockProperties 기본값으로 돌아온다
 *   (12주 차 chaos-control이 모든 파드에 뿌리거나, 그때 ConfigMap·DB 같은 공유 저장소로 옮길지 정한다)
 * - 요청 하나가 시작할 때 한 번 읽는다 (AccountUseCaseImpl). 바꾸는 순간 진행 중이던 요청은 옛 전략으로 끝난다
 */
@Component
class LockStrategySwitch(properties: LockProperties) {

    /** 파드가 뜰 때의 값 (core.lock.strategy). 실험이 끝나면 여기로 되돌린다 */
    val default: LockStrategy = properties.strategy

    private val current = AtomicReference(default)

    val strategy: LockStrategy get() = current.get()

    /** 바꾸고 이전 값을 돌려준다 */
    fun change(to: LockStrategy): LockStrategy = current.getAndSet(to)

    /** 기본값으로 되돌리고 이전 값을 돌려준다 */
    fun reset(): LockStrategy = change(default)
}
