# 00. 당행 송금 transfer → core (예시 풀이)

> 이 프로젝트 3-2주 차(2026-11-14)에 실제로 한 설계를 템플릿으로 다시 정리한 것. 다른 문제를 풀 때 "이 정도 깊이로 쓰면 된다"의 기준

## 1. 흐름

```
고객 ──POST /transfers (Idempotency-Key)──▶ transfer ──POST /postings/transfers (Idempotency-Key = 송금 ID)──▶ core
                                                                     core 안: 원장 기록 + 출금 + 입금 = 로컬 트랜잭션 하나
```
홉은 **transfer → core 하나.** core 안의 출금·입금은 한 트랜잭션이라 홉이 아니다 (ADR-0001이 core를 모놀리스로 둔 이유)

## 2. 홉 분석표

| 홉 | ① 호출 전에 커밋할 흔적 | ② 응답을 못 받으면 상태 | ③ 재시도 멱등 키 | ④ 결과 확정 수단 | ⑤ 실패 시 앞 단계 되돌리기 |
| --- | --- | --- | --- | --- | --- |
| 고객 → transfer | (고객 쪽 일) | 고객 앱: "처리 중, 내역 확인" | 고객 멱등 키 (클라이언트가 만듦) | `GET /transfers/{id}` 또는 같은 키 재요청 | — |
| transfer → core | 송금 행 PROCESSING | UNKNOWN | 송금 ID | core 상태 조회 / 같은 송금 ID 재전송 (core가 멱등) | 앞 단계 없음 (core가 거절하면 core 트랜잭션이 롤백됨) |

## 3. 상태 머신

```
REQUESTED ──▶ PROCESSING ──완료──▶ COMPLETED (끝)
 (메모리만)               ├─거절──▶ FAILED (끝)
                          └─모름──▶ UNKNOWN ──재전송 / 상태 조회──▶ COMPLETED / FAILED
                                     ↺ 재전송이 또 모름
```
- **거절(FAILED)은 "확실히 처리 안 됨"일 때만.** 409·5xx·타임아웃·2xx인데 body 불가는 UNKNOWN
- PROCESSING의 의미는 "호출했을 수도 있다"

## 4. 장애 시나리오

| 언제 죽나 / 무엇이 실패하나 | 그때 데이터 상태 | 누가 어떻게 고치나 |
| --- | --- | --- |
| PROCESSING 커밋 직후, core 호출 전에 transfer 죽음 | transfer: PROCESSING, core: 없음 | 해소 스케줄러(8주 차): 조회 → 없음 → 재전송 또는 실패 처리 |
| core 커밋 후 응답이 유실 (타임아웃) | transfer: UNKNOWN, core: 완료 | 고객 재요청 → 같은 송금 ID 재전송 → core가 replayed로 같은 원장 ID → COMPLETED |
| core 성공, transfer의 결과 커밋(트랜잭션 ②) 실패 | transfer: PROCESSING, core: 완료, 고객은 500 | 해소 스케줄러: 조회 → 완료 → COMPLETED. 송금 ID가 든 에러 로그가 단서 |
| core 500 | transfer: UNKNOWN, core: 모름 | 재전송 / 조회 |
| core 422 (잔액 부족) | transfer: FAILED, core: 롤백 | 끝. 고객은 사유를 고쳐 **새 키**로 |

## 5. 중복·동시성

- 순차 재시도: 고객 멱등 키로 기존 송금을 찾아 지금 상태를 돌려준다 (응답 저장 방식 아님 → 송금 행이 곧 기록)
- 동시 같은 키: 둘 다 INSERT → 늦은 쪽 UNIQUE 위반 → 트랜잭션 밖에서 잡고 이긴 쪽을 읽어 재요청 처리
- 같은 키에 다른 내용 → 409
- transfer → core 구간의 중복은 송금 ID로 core가 막는다 (구간마다 자기 키)

## 6. 대사 포인트

- transfer 송금(`coreEntryId`, 송금 ID) ↔ core 원장(`entryId`, `idempotencyKey`) 1:1 대조
- transfer는 COMPLETED인데 core에 없음 / core에 있는데 transfer는 FAILED → 즉시 조사 대상
- PROCESSING·UNKNOWN에 오래 머문 건 (에이징)

## 7. 이 예시에서 일부러 안 다룬 것

- 되돌릴 수 없는 외부 → 04(타행 이체)에서 보상
- 시간이 지나면 바뀌는 상태 → 03(좌석 예약)
- 부분 처리 → 06(부분 취소)
