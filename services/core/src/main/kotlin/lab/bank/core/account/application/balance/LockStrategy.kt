package lab.bank.core.account.application.balance

/**
 * 잔액을 바꿀 때 동시성을 어떻게 다루나 (7주 차 실험 스위치). 구현: account/infrastructure/balance/
 *
 * NONE          읽고 → 앱에서 계산 → 버전 검사 없이 덮어쓴다. **재현용** (lost update)
 * OPTIMISTIC    @Version. 커밋 때 충돌을 감지하면 posting이 트랜잭션째 다시 시도
 * PESSIMISTIC   SELECT ... FOR UPDATE. 먼저 잡은 쪽이 끝날 때까지 기다린다
 * ATOMIC_UPDATE 조건부 UPDATE 한 문장 (balance = balance - :amount WHERE balance >= :amount). 앱으로 읽어 오지 않는다
 */
enum class LockStrategy { NONE, OPTIMISTIC, PESSIMISTIC, ATOMIC_UPDATE }
