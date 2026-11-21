# 질문 은행: 동시성 제어 (CC)

설명 자료: `docs/learning/concurrency.md`, pitfalls 32·33·34

### CC-001 · 개념 · ★
**Q.** 동시성 때문에 데이터가 틀리는 방식을 종류별로 나눠 보세요. 각각 무엇으로 막나요?

<details><summary>답 핵심</summary>

- lost update: 둘 다 같은 값을 읽고 각자 써서 하나가 사라짐 → 상대값 SET, 락, version
- 검사 후 행동(TOCTOU): 둘 다 "잔액 충분"을 확인하고 둘 다 뺌 → 음수 잔액 → 검사와 변경을 한 원자적 단위로 (조건부 UPDATE의 WHERE, 락)
- 중복 요청: 같은 요청이 두 번 → **락이 아니라 멱등 키(UNIQUE)**. 락은 "동시에"만 막고 1초 간격 두 번은 못 막는다
- 📎 docs/learning/concurrency.md 1절, `LockStrategyConcurrencyTest` NONE 재현
</details>

- 출처: 2026-11-22 원자적 UPDATE 질답

### CC-002 · 개념 · ★
**Q.** `UPDATE account SET balance = balance - :amt WHERE id = :id AND balance >= :amt`가 동시 출금에 안전한 이유는? 락은 안 걸리나요?

<details><summary>답 핵심</summary>

- UPDATE는 대상 행에 쓰기 잠금을 건다 → 두 번째 요청은 첫 번째 커밋까지 대기
- 첫 번째가 커밋하면 PostgreSQL(READ COMMITTED)이 **최신 커밋 값으로 WHERE를 다시 평가** → 잔액이 모자라면 0행
- 판단과 변경이 한 문장이라 "읽고-계산하고-쓰기" 사이의 틈이 없다
- 락이 없는 게 아니다: UPDATE부터 커밋까지 행 잠금을 쥔다
- 📎 concurrency.md 2절, `AccountRepository.withdrawAtomically`, pitfalls 33
</details>

- 출처: 2026-11-22

### CC-003 · 꼬리 · ★★
**Q.** 그 조건부 UPDATE에서 `SET balance = balance - :amt`(상대값)만 있고 WHERE 조건이 없다면 무엇은 막히고 무엇은 안 막히나요?

<details><summary>답 핵심</summary>

- 막힘: lost update. DB가 잠금을 잡은 뒤 최신 값에서 빼므로 출금 두 건이 다 반영된다
- 안 막힘: 마이너스 잔액. 앱에서 "잔액 충분"을 각자 확인하고 둘 다 빼면 100원 계좌가 -100원
- 그래서 WHERE 조건이 **업무 규칙이자 동시성 처리**다 (규칙은 업무, 그걸 WHERE에 넣는 게 동시성)
- 우리 테이블엔 `CHECK (balance >= 0)`이 없어서 -100이 조용히 저장된다
- 📎 concurrency.md 1·2절
</details>

- 출처: 2026-11-22 ("그냥 업무상 예외네?"에 대한 정정)

### CC-004 · 꼬리 · ★★
**Q.** 조건부 UPDATE를 못 쓰는 경우는 언제인가요? 반대로 "못 쓸 것 같지만 실은 쓸 수 있는" 경우는?

<details><summary>답 핵심</summary>

- 갈림길: **판단에 필요한 정보가 그 행 안에 다 있는가**
- 못 씀: 다른 행·테이블을 봐야 함(일일 한도 = 오늘 원장 합계), 외부 호출(이상 거래 탐지), 새 값을 SET 식 하나로 못 씀(이자·수수료 구간)
- 갈아탈 이유가 아님: 바뀐 값이 필요 → `RETURNING`(표준 JPQL엔 없어 네이티브), 실패 원인이 필요 → 0행이면 재조회 (메시지만 부정확할 수 있고 돈은 안전)
- 요령: 필요한 값을 같은 행으로 끌어오면 다시 가능 (`today_withdrawn` 컬럼 + 날짜 바뀔 때 초기화)
- 📎 concurrency.md 2절, pitfalls 33
</details>

- 출처: 2026-11-22

### CC-005 · 장애 · ★★
**Q.** 조건부 UPDATE 코드에서 반환값(바뀐 행 수) 확인 한 줄을 빠뜨리면 무슨 일이 생기나요? 언제 드러나나요?

<details><summary>답 핵심</summary>

- DB는 0행이어도 오류를 내지 않는다
- 우리는 원장을 먼저 기록 → 잔액 부족으로 0행인데 확인을 안 하면 원장엔 출금, 잔액은 그대로 → 원장과 잔액 불일치
- 요청은 성공으로 응답되고, 일일 대사를 돌려야 드러난다 (조용한 불일치)
- 반환값 확인이 곧 잔액 검사다
- 📎 pitfalls 33, `AtomicUpdateBalanceChanger`
</details>

- 출처: 2026-11-22

### CC-006 · 개념 · ★
**Q.** JPA `@Version`은 정확히 무엇을 해 주나요? 직접 구현하면 무엇을 손으로 해야 하나요?

<details><summary>답 핵심</summary>

- 세 가지: UPDATE에 `AND version = ?` 붙이기, `version + 1`, 바뀐 행 수 확인 → 0행이면 예외 (Hibernate StaleObjectState → JPA OptimisticLock → Spring `ObjectOptimisticLockingFailureException`)
- 충돌은 **커밋 직전 flush**에서 드러난다
- 직접 구현: 조건부 UPDATE(`WHERE id = ? AND version = :읽은값`) + 0행이면 `OptimisticLockingFailureException`을 직접 던짐 → 기존 재시도 루프 그대로 사용. 더티 체킹이 또 UPDATE하지 않게 엔티티 detach
- 0행의 뜻이 둘(충돌 / 없는 계좌)이라 구분 필요
- 📎 concurrency.md 4절, `NoLockBalanceChanger.overwriteBalance`(여기서 조건 한 줄과 행 수 확인만 빠진 것)
</details>

- 출처: 2026-11-22

### CC-007 · 꼬리 · ★★
**Q.** 낙관적 락 충돌이 나면 재시도는 어디서, 왜 거기서 하나요? 재시도 사이엔 무엇을 넣나요?

<details><summary>답 핵심</summary>

- 트랜잭션 **바깥**에서, 처음부터 새 트랜잭션으로 (`PostingUseCaseImpl.retryOnOptimisticLock`)
- 충돌한 트랜잭션은 이미 롤백 대상이고 들고 있는 엔티티도 옛 값 → 같은 트랜잭션 안에서는 못 고친다. 원장 기록부터 다시 하므로 멱등 판정도 다시
- 같은 클래스의 @Transactional을 this로 부르면 프록시를 안 거친다 → TransactionTemplate으로 경계를 코드에 드러냄
- 지터(무작위 대기, 시도마다 조금 더): 같이 충돌한 요청이 같은 순간에 또 부딪히지 않게
- 다 써도 충돌 → 지금은 500 (503 + 재시도 안내가 더 정확, 후속)
- 📎 `PostingUseCaseImpl`, 핫 계좌 테스트 (성공 20 / 충돌 실패 7 / 잔액 부족 13)
</details>

- 출처: 2026-11-22 ("지금 재시도 로직은 없지?")

### CC-008 · 비교 · ★★
**Q.** 낙관적 락과 조건부 UPDATE는 메커니즘이 같다고들 합니다. 무엇이 같고 무엇이 다른가요? 핫 계좌에서 결과가 어떻게 달라지나요?

<details><summary>답 핵심</summary>

- 같음: 둘 다 "조건부 UPDATE + 바뀐 행 수 확인"
- 다름: WHERE가 묻는 것. 낙관적 락 = `version = 읽은값` (내가 읽은 뒤 **아무도** 안 바꿨나), 조건부 UPDATE = `balance >= amt` (**업무 조건**이 지금도 맞나)
- 그래서 낙관적 락은 잔액이 충분해도 동시 입금 하나 때문에 실패한다 → 핫 계좌에서 재시도 폭증·충돌 실패 (테스트: 충돌 실패 7 vs 0)
- 대신 낙관적 락은 범용: 규칙을 도메인 메서드에 그대로 둔다
- 📎 concurrency.md 4절
</details>

- 출처: 2026-11-22

### CC-009 · 상황 적용 · ★★
**Q.** 다음 각각에 어떤 동시성 제어를 쓰겠어요? ① 상품 재고 차감 ② 관리자가 화면에서 상품 설명을 고쳐 저장 ③ 일일 이체 한도(오늘 이체 합계)가 있는 출금 ④ 같은 주문으로 외부 PG 결제 API가 두 번 호출되는 것 방지

<details><summary>답 핵심</summary>

- ① 조건부 UPDATE (`stock = stock - 1 WHERE stock > 0`): 판단 정보가 그 행에 다 있음
- ② 낙관적 락: 읽기(화면 열기)와 쓰기(저장)가 **다른 요청**에 걸침, 사용자 생각 시간 동안 잠금을 쥘 수 없어 version을 들고 다님. 다른 전략은 못 함
- ③ 비관적 락(계좌 행 FOR UPDATE 후 오늘 합계 조회·판단) 또는 합계를 같은 행 컬럼으로 끌어와 조건부 UPDATE
- ④ 분산 락(지킬 대상이 DB 행이 아니라 외부 호출) + 근본은 멱등 키 (PG에 같은 키 전달)
- 판단 순서: 정보가 행 안에 있나 → 끌어올 수 있나 → DB 안에서 끝나나 → DB 밖인가 → 그래도 한 행에 몰리면 구조 변경
- 📎 concurrency.md 0절·7절 비교표
</details>

- 출처: 2026-11-22 ("어떤 상황에서 어떤 걸 적용하는 게 좋은지")

### CC-010 · 장애 · ★★★
**Q.** Redis 분산 락을 `@Transactional` 메서드 안에서 `lock() → 잔액 변경 → finally unlock()`으로 감쌌습니다. 무엇이 깨지고, 어떻게 고치나요? 우리 테스트에선 어떻게 보였나요?

<details><summary>답 핵심</summary>

- DB 행 잠금은 커밋 때 DB가 풀지만 Redis 락은 unlock()을 부르는 순간 풀린다. `@Transactional` 메서드의 커밋은 메서드가 **끝난 뒤** 프록시가 하므로 finally의 unlock도 커밋 전
- 틈: 락은 풀렸는데 변경은 커밋 전 → 다음 요청이 옛 잔액을 읽음
- 재현(`RedisLockScopeTest`): 버전 검사 없이(NONE) 출금 2건 성공에 잔액 1건만 빠짐(10,000 → 9,000). `@Version`이 있으면 늦은 커밋이 `OptimisticLockingFailureException`
- 고침: `락 → 트랜잭션 시작 → 변경 → 커밋 → 락 해제`. 우리는 account가 감싸는 함수(`AccountLocks`)를 내주고 트랜잭션을 여는 posting이 그 안에서 연다
- 안전망(@Version)을 남기면 "재시도 0"이 락 정상 동작의 지표가 된다
- 📎 pitfalls 34, `RedisLockScopeTest`, `RedisLockConcurrencyTest`
</details>

- 출처: 2026-11-22

### CC-011 · 우리 프로젝트 · ★★
**Q.** Redis 락 대기 상한을 왜 1초로 했나요? 5초로 두면 무슨 일이 생기나요? 못 잡으면 몇 번으로 응답하나요?

<details><summary>답 핵심</summary>

- transfer → core read-timeout이 3초. 대기가 그보다 길면 transfer는 3초에 끊고 "결과 불명"으로 두는데, core는 4초째 락을 잡아 출금을 끝낸다 → 끊긴 요청이 나중에 성공 = UNKNOWN의 원인
- 원칙: **락 대기는 부르는 쪽 타임아웃보다 짧게**
- 못 잡으면 `AccountLockTimeoutException` → 503 (처리 안 함이 확실, 다시 시도하면 됨)
- 두 계좌(송금)여도 대기 상한은 마감 시각 하나 (계좌마다 1초씩이면 2초)
- 📎 `LockProperties.Redis`, `RedisAccountLocks`, transfer `application.yaml` read-timeout
</details>

- 출처: 2026-11-22

### CC-012 · 비교 · ★★
**Q.** 동시성 제어 전략별로 공정성(먼저 온 요청이 먼저 처리되나)은 어떤가요? 공정성의 대가는?

<details><summary>답 핵심</summary>

- 공정: 비관적 락·조건부 UPDATE (PostgreSQL 잠금 대기열이 도착 순서, 굶주림 방지를 위해 일부러 대기열을 거침), Redisson `RFairLock`, 계좌별 단일 처리자 큐(완전, 대신 API가 비동기)
- 불공정: 낙관적 락(먼저 커밋한 쪽 승), Redisson RLock·스핀(새로 온 쪽이 끼어듦)
- 대가: 불공정 락은 실행 중인 스레드가 바로 채 가서(barging) 처리량이 높고, 공정 락은 깨우는 비용 때문에 느리다 (`ReentrantLock(true)` Javadoc: 종종 훨씬 느림)
- 그래서 측정할 땐 p99뿐 아니라 최대 지연을 본다
- 📎 concurrency.md 6절
</details>

- 출처: 2026-11-22 ("공정성 있는 건 원자적 업데이트밖에 없나?")
