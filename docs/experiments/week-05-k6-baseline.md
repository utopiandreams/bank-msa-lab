# Week 05. k6 첫 기준선 (당행 송금)

> 2026-11-18. 관측성 기본 스택(Prometheus·Grafana)을 올린 직후, 노드 확장 전 클러스터(물리 호스트 1대, VM 4대)에서 측정.
> 이후 모든 실험(7주 차 락, 14주 차 게이트웨이, 15주 차 서킷 브레이커, 노드 확장)의 "전" 숫자.

## 문제
이후 실험의 전후 비교에 쓸 출발점이 없다. 그리고 transfer → core read-timeout 3초가 근거 없는 임시값이다.

## 재현 (측정 방법)
- 흐름: k6(맥북) → Traefik → gateway → transfer → core, 당행 송금 1~100원
- 계좌: 200개 × 100만 원 (`load-test/seed-accounts.sh`, 입금은 port-forward 관리자 통로). 무작위로 서로 다른 두 계좌
- 부하: `ramping-arrival-rate` 워밍업 5 → 10 → 30 → 60건/초, 단계마다 2분 (워밍업 1분), 단계 사이 10초 상승
- 측정 위치 두 곳: **k6(고객 자리)** vs **서버 Micrometer `http.server.requests` p99** (Prometheus `histogram_quantile`, 단계 구간 `increase`)
- 환경: 맥북 **Wi-Fi** (노드까지 ping 평균 4.9ms / 최대 8.7ms), 클러스터는 proxmox 1대(E5-2697 v3) 위 VM 4대, 서비스 replicas 1
- 스크립트: `load-test/transfer-baseline.js`, 원본 출력: `load-test/results/` (Git 밖)

## 결과

| 단계 | k6 p99 | gateway p99 | transfer p99 | core p99 | 노드 CPU 최대 |
| --- | --- | --- | --- | --- | --- |
| 워밍업 5/s | 38.3ms | 33.0ms | 27.9ms | 9.8ms | 8% |
| 10/s | 32.8ms | 27.5ms | 23.4ms | 8.4ms | 9% |
| 30/s | 26.0ms | 21.8ms | 16.1ms | 6.6ms | 14% |
| 60/s | 31.7ms | 20.9ms | 16.4ms | 6.9ms | 22% |

- 전체: 송금 13,049건, HTTP 에러 0%, **COMPLETED 13,046 / UNKNOWN 3** / FAILED 0
- 잔액 정합성: 시작 2억 원 = 끝 2억 원 ✅ (UNKNOWN 3건 포함해도 합 불변 → core 쪽은 원자적으로 롤백됨)
- 포화 신호: core HikariCP 활성 커넥션 최대 **1**, 대기 0. 60건/초는 이 시스템에 아주 가벼운 부하

### 판독
1. **하키 스틱은 아직 없다.** 60건/초에서 CPU 22%, 커넥션 1개. 꺾이는 지점을 찾으려면 수백 건/초까지 올려야 한다 (다음 측정)
2. **부하가 늘수록 오히려 빨라졌다** (서버 쪽도 같음 → 맥북 Wi-Fi 탓만은 아님). 가설: 한가할 때 CPU가 절전 상태로 내려가 요청마다 깨어나는 비용, JIT 컴파일이 계속 진행. 검증은 안 함 (호스트 CPU 주파수 지표가 없음)
3. **k6 − gateway ≈ 5~11ms** = 맥북 Wi-Fi + Traefik 구간. 서버 대시보드에는 안 보이는 고객 쪽 시간
4. **hop별 시간:** core 약 7ms, transfer는 core 호출 외에 약 9ms(자기 DB 트랜잭션 두 번 + Kafka 발행), gateway 약 5ms
5. **read-timeout 3초의 근거:** core p99 7~10ms. 3초는 p99의 300배 → 15주 차에 근거 있게 줄일 재료 (지금 바꾸지는 않음)

### 🔎 예상 못 한 발견: 기준선에서 동시성 충돌 (UNKNOWN 3건)
- 시각: 16:46:06 (60건/초 단계), 0.3초 안에 3건
- core 로그: `ObjectOptimisticLockingFailureException` — `Account`의 `@Version` 낙관적 락 충돌. 두 송금이 **같은 계좌를 거의 동시에** 건드림 (계좌 200개여도 생일 문제처럼 충돌은 생긴다)
- core는 이 예외를 매핑하지 않아 **500** → transfer는 500을 규칙대로 **결과 불명(UNKNOWN)**으로 분류
- 돈은 안전: core 트랜잭션은 롤백됐고 잔액 합 불변. 하지만 고객은 "모름" 응답을 받았고, 그 송금은 8주 차 해소 스케줄러가 생기기 전까지 UNKNOWN에 머문다
- 사실은 **확정된 실패**(롤백 완료)인데 "모름"으로 보이는 것 → pitfalls 22
- 이건 7주 차(동시 출금·락 전략) 주제가 먼저 나타난 것. 어떻게 다룰지(409 + 재시도 / core 안에서 재시도 / 비관적 락)는 7주 차 설계 결정으로 넘긴다

### 측정의 한계
- 서버 p99는 Micrometer 기본 히스토그램 버킷을 보간한 값이라 수 ms 단위는 대략적이다
- 단계당 2분, 1회 측정. 반복 측정 편차는 아직 모른다
- 맥북 Wi-Fi 경유. 12주 차 k6-operator(클러스터 안 실행)와 비교 예정
- Tomcat 스레드 지표(`tomcat.threads.busy`)가 안 나온다 (`server.tomcat.mbeanregistry.enabled` 필요) → 꺾이는 지점을 찾는 다음 측정 전에 켤 것

## 다음
- 부하를 100 → 200 → 400건/초 이상으로 올려 하키 스틱과 **무엇이 먼저 막히는지**(CPU / Hikari / Tomcat 스레드 / Kafka) 찾기. 낙관적 락 충돌이 부하에 따라 어떻게 늘어나는지도 같이
- 노드 확장 후 같은 스크립트로 재측정

## 배운 점
- 기준선은 "숫자 확인"이 아니라 **평소 모양 확인**이다. 숫자보다 더 큰 소득은 예상 못 한 동시성 충돌을 찾은 것
- 고객 자리(k6)와 서버(Micrometer)를 나란히 재야 그 사이 구간이 보인다
