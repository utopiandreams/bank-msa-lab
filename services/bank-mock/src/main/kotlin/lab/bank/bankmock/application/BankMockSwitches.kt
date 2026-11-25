package lab.bank.bankmock.application

import org.springframework.stereotype.Component

/**
 * 외부 은행의 행동(실험 스위치)을 **재시작 없이** 바꾸는 자리. 시작값은 설정(BankMockProperties), 이후는 실험 스위치 API로.
 *
 * 왜 (2026-11-26): 전에는 환경변수를 바꿔 push → 파드 재시작이었다. 장애를 거는 행위 자체가 재시작을 일으키면
 * "장애의 효과"와 "재시작의 효과"가 섞인다. 재시작하면 설정값으로 돌아온다 (core 락 전략 스위치와 같은 규칙)
 */
@Component
class BankMockSwitches(initial: BankMockProperties) {

    // kotlin-spring 플러그인이 @Component를 open으로 만들어 "private set"을 못 쓴다 → 뒷받침 필드를 따로
    @Volatile
    private var settings: BankMockProperties = initial

    val current: BankMockProperties get() = settings

    /** 바꾼 결과를 검증(BankMockProperties init)한 뒤에만 반영. (이전, 이후)를 돌려준다 */
    @Synchronized
    fun change(update: (BankMockProperties) -> BankMockProperties): Pair<BankMockProperties, BankMockProperties> {
        val previous = current
        val next = update(previous)
        settings = next
        return previous to next
    }
}
