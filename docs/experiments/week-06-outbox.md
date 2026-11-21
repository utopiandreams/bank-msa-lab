# Week 06. 이벤트 유실(Transactional Outbox)과 중복 수신(멱등 컨슈머)

> 2026-11-21. transfer → Kafka(`transfer-events`) → notification. 클러스터: Strimzi Kafka 4.3.2 브로커 1대(KRaft), transfer·notification replicas 1.

## 문제
- **유실:** transfer는 송금 상태를 커밋한 **뒤** 트랜잭션 밖에서 Kafka로 보냈다 (4주 차에 의도적으로 남긴 구멍). 커밋과 발행 사이에 죽거나 Kafka가 없으면 송금은 끝났는데 이벤트는 사라진다. 로그 한 줄 말고는 다시 보낼 근거가 없다.
- **중복:** 컨슈머가 처리를 끝내고 오프셋을 커밋하기 전에 죽으면 같은 메시지를 다시 받는다 (at-least-once). Outbox 릴레이도 "보냈는데 표시 전에 죽음"이면 같은 이벤트를 다시 보낸다.

## 재현

### 유실 (테스트, `TransferEventLossTest`)
- 프로듀서가 브로커에 붙기 **전에** Kafka 컨테이너를 `docker pause` → 송금 → `send()`가 `max.block.ms`(1초) 뒤 예외 → 재개
- 결과: 송금은 `COMPLETED`, Kafka 복구 뒤 20초를 기다려도 그 송금의 이벤트는 없음. 대조군(복구 뒤 송금)은 정상 발행
- **첫 시도 실패에서 배운 것:** 연결된 뒤에 pause하면 프로듀서는 `delivery.timeout.ms` 뒤 "실패"를 보고하지만, 커널 소켓 버퍼에 들어간 요청을 Kafka가 깨어나며 처리해 **실제로는 기록됐다.** 발행 타임아웃 ≠ 미발행 (pitfalls 29)

### 중복 수신 (클러스터)
- 오프셋 되감기 = "처리 후 커밋 전에 죽음"의 재현. 컨슈머는 다시 뜨면 **마지막으로 커밋한 오프셋**부터 읽으므로, 커밋 오프셋을 처리한 지점보다 앞으로 돌리면 같은 상황이 된다
- notification 0대 → `kafka-consumer-groups --reset-offsets --to-datetime 10:06:00Z` (파티션 0은 3칸, 1은 2칸 = 5건) → notification 1대

## 선택지 비교

| 방법 | 장점 | 단점 |
| --- | --- | --- |
| 커밋 뒤 바로 발행 (전) | 단순, 즉시 | 커밋과 발행 사이 장애 = **유실** (복구 불가) |
| 커밋 전에 발행하고 ack 뒤 커밋 | 유실 없음 | ack 뒤 커밋 실패 = **유령 이벤트** (회수 불가), Kafka 장애 = 송금 장애 (pitfalls 30) |
| **Outbox + 폴링 릴레이** (채택) | 최악이 "중복"(컨슈머 멱등으로 복구), Kafka 장애가 송금에 안 번짐, 새 인프라 없음 | 폴링 간격만큼 지연, DB에 주기적 쿼리, 릴레이 동시성 처리 필요 |
| Outbox + CDC (Debezium) | 지연 짧음, 앱 코드 없음 | Kafka Connect·Debezium 운영, 논리 복제 슬롯 관리 (안 읽으면 WAL이 쌓여 디스크 참) |

릴레이 동시성 (replicas ≥ 2):

| 방법 | 장점 | 단점 |
| --- | --- | --- |
| **`FOR UPDATE SKIP LOCKED`** (채택) | 추가 라이브러리 없음, 파드 수만큼 처리량 | DB 기능 의존 (MySQL 8.0+, 인덱스 없으면 넥스트키 락으로 범위가 커짐) |
| ShedLock (리더 하나) | 순서 유지 쉬움 | 새 라이브러리, 처리량이 파드 하나에 묶임 |
| 조건부 UPDATE로 선점(리스) | DB 무관 | 컬럼·만료 시간 설계 |

## 적용한 해결책과 이유

**Outbox** (Flyway V6 `outbox_event`, `infrastructure/outbox/`)
- 발행 포트(`TransferEventPublisher`)는 그대로 두고 구현만 outbox INSERT로 바꿨다 → 유스케이스는 호출 위치를 트랜잭션 **안**으로 옮긴 것뿐. 헥사고날이 값을 한 지점
- 포트 계약을 뒤집었다: "실패해도 예외를 던지지 않는다" → "실패하면 예외, 송금 상태 변경도 함께 롤백". 롤백되면 송금은 의도 상태에 남아 결과 불명처럼 재시도된다
- eventId = outbox 행 ID, INSERT 때 한 번 정한다 → 릴레이가 재시도해도 같은 eventId로 나가 컨슈머가 거른다
- 릴레이: 1초마다 한 회차 = 한 트랜잭션 (SKIP LOCKED로 잡기 → 한 건씩 보내고 **ack 확인** → `markPublished`). 한 건 실패하면 그 회차 중단 (대개 Kafka 장애라 나머지도 건마다 타임아웃). 트랜잭션 안의 원격 호출은 잠금을 쥔 채 보내야 해서 생긴 의도적 예외, 상한은 batch-size·send-timeout
- 정리: 매일 04:00 발행 후 7일 지난 행 삭제. 락 없음 (두 번 돌아도 같은 결과). 미발행 행은 지우지 않는다 (들여다볼 대상)
- 실험 스위치: `outbox.relay.enabled`, `poll-interval`, `batch-size`, `send-timeout`, `cleanup.retention`

**멱등 컨슈머** (4주 차 K-3에서 이미 구현, 이번엔 클러스터에서 증명)
- notification은 `event_id` UNIQUE로 PENDING 행을 먼저 INSERT → 이미 있으면 `DUPLICATE`로 건너뜀

## 결과

### 유실: Kafka 브로커 파드 삭제 (클러스터)

| 시각 (UTC) | 일 |
| --- | --- |
| 10:06:31 | `kubectl delete pod bank-kafka-dual-role-0` |
| 10:06:38~54 | 송금 5건 모두 `COMPLETED`, outbox 미발행 5, 릴레이 `발행 실패, 이번 회차 중단` 13회 |
| 10:07:02 | Kafka Ready (중단 약 31초) |
| 10:07:05 | 릴레이 한 회차에 5건 발행 → notification 5건 `SENT` (송금마다 1건) |

| 지표 | 전 (커밋 뒤 바로 발행) | 후 (Outbox) |
| --- | --- | --- |
| Kafka 중단 중 끝난 송금의 이벤트 | 유실 (테스트로 재현) | **5/5 전달** |
| 이벤트 지연 | 즉시 (또는 영원히 안 감) | Kafka 복구 뒤 3초 이내 (중단 포함 10~27초) |
| Kafka 장애가 송금 API에 주는 영향 | 건마다 `max.block.ms` 1초 대기 | 없음 (송금은 DB에만 쓴다) |

### 중복 수신: 오프셋 되감기 (클러스터)

| 지표 | 값 |
| --- | --- |
| 다시 받은 메시지 | 5건 (offset 35896~35898, 36357~36358) |
| 컨슈머 판정 | 5건 모두 `DUPLICATE` |
| 알림 행 수 | 108,125 → 108,125 (변화 없음) |
| 컨슈머 그룹 랙 | 0 |

### SKIP LOCKED (테스트, `OutboxSkipLockedTest`)
- 릴레이 A가 5건을 잡고 잠금을 쥔 동안 릴레이 B가 10건을 요청 → B는 나머지 5건만, 겹침 0
- `SKIP LOCKED`를 빼면 B가 A의 커밋까지 막혀 테스트 실패 (TimeoutException) → 테스트가 실제로 SKIP LOCKED를 검증함을 확인

## 대시보드 판독
- 이번 실험은 DB·로그로 확인했다. 10주 차 RED 대시보드 뒤에 다시 하면 볼 것: outbox 미발행 행 수(지표 추가 필요)가 Kafka 중단 동안 계단처럼 오르다 한 번에 0으로 떨어지는 모양, 그 사이 송금 API 지연은 평평함

## 배운 점 / 한계
- dual write는 없앨 수 없다. 순서를 정해 장애 결과를 고른다: 되돌릴 수 없는 쪽보다 우리 DB에 먼저 확정 (outbox 행 = 이벤트판 의도 상태, 5주 차 사가와 같은 구조)
- Outbox는 "최소 한 번"이다. Outbox와 컨슈머 멱등은 짝
- 발행 타임아웃 ≠ 미발행. Kafka에도 결과 불명이 있다
- GitOps selfHeal은 손으로 일으킨 장애도 되돌린다 → 실험 중엔 자동 동기화를 잠시 끈다 (pitfalls 31). 12주 차 Chaos Mesh는 Argo가 관리하지 않는 리소스로 장애를 주입하므로 이 충돌이 없다
- **한계:** outbox 미발행 행 수·가장 오래된 미발행 나이 지표가 없다 (13주 차 패널 "PROCESSING·UNKNOWN 체류 시간"과 함께). 한 송금의 이벤트가 둘 이상이 되면 SKIP LOCKED 병렬 릴레이에서 순서가 뒤집힐 수 있다 (지금은 송금당 끝 이벤트 하나). 릴레이가 같은 행을 계속 실패하면(깨진 행) 그 뒤가 막힌다 → 시도 횟수·격리는 필요해지면
