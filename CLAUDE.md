# CLAUDE.md

이 파일은 이 저장소에서 작업하는 Claude Code를 위한 안내서다. 작업을 시작하기 전에 반드시 끝까지 읽는다.

## 1. 프로젝트 개요

송금·결제를 흉내 내는 **미니 뱅킹 MSA**를 온프레미스 Kubernetes에서 운영하는 학습용 사이드 프로젝트다.

- **목적:** 현업에서 겪어 보지 못한 MSA 문제(분산 트랜잭션, 중복 메시지, 동시성, 장애 전파, 관측성)를 **일부러 재현하고, 해결하고, 전후를 수치로 측정**한다.
- **최종 산출물:** 동작하는 시스템보다 **재현 → 원인 → 선택지 비교 → 결과 수치**의 기록이 더 중요하다. 이 기록이 이력서와 면접 답변이 된다.
- **포트폴리오의 얼굴:** 버튼 하나로 부하와 장애를 일으키고, 시스템이 무너지고 회복하는 모습을 한 화면에서 실시간으로 보여주는 **장애 실험 컨트롤 패널**. 면접에서 화면 공유로 시연하는 것이 목표라 **시각적 완성도가 매우 중요하다** (9장 참고).
- **작업 시간:** 개발자는 육아 중이라 주 8~10시간, 주로 밤에 작업한다. 한 세션에서 끝낼 수 있는 작은 단위로 나눠서 진행한다.

## 2. 아키텍처

| 서비스 | 역할 |
| --- | --- |
| `gateway` | Spring Cloud Gateway (WebFlux, 논블로킹). 라우팅, JWT 인증 |
| `core` | 계정계 코어. 모듈형 모놀리스(DB 하나): `account` 모듈(계좌·잔액) + `ledger` 모듈(복식부기 원장, 대사의 기준). 송금 ID 기준 멱등 처리, 상태 조회 API |
| `transfer` | 송금 요청 기록과 상태 머신, 고객 멱등 키(Idempotency-Key), 상태 조회로 결과 불명 해소, 타행 이체 사가 지휘 |
| `notification` | 송금 결과 알림. Kafka 이벤트 구독 |
| `bank-mock` | 외부 은행 Mock. 지연·실패·타임아웃을 설정으로 주입할 수 있어야 함 |
| `chaos-control` | 컨트롤 패널의 백엔드. k6-operator로 부하 실행, Chaos Mesh로 파드 종료·네트워크 지연, 각 서비스의 실험 스위치 변경, 진행 상황을 SSE로 전달 |
| `web` | 장애 실험 컨트롤 패널 (React). 시나리오 버튼, 실험 스위치, 실시간 지표, Grafana 패널 임베드 |

```
[web 컨트롤 패널] ──버튼──▶ [chaos-control API]
                               ├─ 부하:  k6-operator (TestRun 리소스)
                               ├─ 장애:  Chaos Mesh (PodChaos, NetworkChaos)
                               └─ 스위치: 각 서비스의 /internal/experiments API
[web] ◀──SSE── chaos-control ◀── Prometheus 쿼리
[web] ◀──iframe── Grafana (Prometheus · Tempo · Loki)
```

구조 결정 (상세: `docs/adr/0001-transfer-usecase-and-service-boundaries.md`): **계정계 코어 모놀리스 + 주변 MSA.** 당행 이체는 코어 안의 로컬 트랜잭션, 분산 경계는 실제로 갈라지는 곳(`transfer ↔ core`, `core ↔ bank-mock`)에 둔다. 사가는 롤백할 수 없는 외부가 끼는 타행 이체에서 다룬다.

도메인 원칙 (상세: `docs/domain/README.md`):
- **상품의 가짓수가 아니라 "돈이 움직이는 방식"을 실제 금융처럼 만든다.** 입출금 계좌 하나 + 복식부기 원장, 거래 불변, 결과 불명 상태, 일일 대사, 감사 로그, 멱등성. 적금·여신·카드는 만들지 않는다.
- **원장이 진실(source of truth).** 타행 이체는 미정산 임시 계정을 거친다.
- 금융결제원은 따로 만들지 않고 `bank-mock`이 외부 결제망 전체를 대표한다. 2~4주 차는 당행 이체만, 타행은 5주 차(사가)에 추가하고 8주 차에 결과 불명·대사를 더한다.

원칙:
- **DB per service.** 서비스끼리 DB를 공유하지 않는다. `core` 안의 `account`·`ledger` 모듈은 DB 하나를 쓰되 스키마를 나누고, 서로의 테이블에 직접 접근하지 않고 공개 인터페이스로만 호출한다. 다른 서비스의 데이터는 API나 이벤트로만 얻는다.
- 서비스 간 비동기 통신은 Kafka, 파티션 키는 기본적으로 계좌 ID. 단, 이벤트마다 "순서가 필요한 단위"로 정한다: 송금 완료 이벤트는 **송금 ID** (ADR-0003, 2026-11-18)
- 이벤트 발행은 Phase 2부터 반드시 Transactional Outbox를 거친다.

## 3. 기술 스택

- **언어/프레임워크:** Kotlin, Java 25 (LTS), Spring Boot 4.1.x, Spring Cloud 2025.1.x, Spring Data JPA, Gradle (Kotlin DSL) 멀티모듈
  - 2026-11-11 결정: Boot 3.x는 Initializr에서 더 이상 선택할 수 없어서 4.1(당시 안정 기본값)로 정했다. Java 25는 가상 스레드 pinning 해결(JEP 491)을 14주 차 실험에서 21과 비교하려고 골랐다.
- **게이트웨이:** Spring Cloud Gateway (WebFlux, Netty)
- **메시징:** Kafka (Strimzi 오퍼레이터)
- **저장소:** PostgreSQL (서비스별 DB), Redis
- **복원력:** Resilience4j
- **관측성:** OpenTelemetry (Java agent + Collector), Tempo, Prometheus, Loki, Grafana
- **인프라/배포:** 온프레미스 Kubernetes (Proxmox VM 위 클러스터), Terraform (VM 프로비저닝), Helm, Argo CD (GitOps)
  - 인프라 구성·결정·트러블슈팅은 전부 `docs/infra/`에 기록한다. 인프라 작업 전에 `docs/infra/README.md`부터 읽는다.
- **부하·장애 주입:** k6, k6-operator, Chaos Mesh
- **프론트엔드:** React, TypeScript, Vite, Tailwind CSS, 차트는 Recharts 또는 ECharts, 실시간 갱신은 SSE
- 버전은 `gradle/libs.versions.toml`에서 관리한다. 새 라이브러리를 추가하기 전에 개발자에게 먼저 묻는다.

## 4. 저장소 구조 (목표)

```
.
├── CLAUDE.md
├── settings.gradle.kts
├── gradle/libs.versions.toml
├── services/
│   ├── gateway/
│   ├── core/              # account · ledger 모듈
│   ├── transfer/
│   ├── notification/
│   ├── bank-mock/
│   └── chaos-control/
├── web/                   # 컨트롤 패널 프론트엔드 (pnpm, Vite)
├── libs/
│   └── common/            # 공통 이벤트 스키마, 에러 모델 등 (최소한으로 유지)
├── infra/
│   └── terraform/         # Proxmox VM · k8s 클러스터 프로비저닝
├── deploy/
│   ├── helm/              # 서비스별 Helm 차트 (web 포함)
│   ├── argocd/            # Argo CD Application 매니페스트
│   ├── chaos/             # Chaos Mesh 실험 템플릿
│   └── grafana/           # 대시보드 JSON (코드로 관리)
├── load-test/             # k6 시나리오 (패널 버튼과 1:1로 대응)
└── docs/
    ├── worklog.md         # 세션별 작업 일지 + 이력서 소재 (6장 7번)
    ├── experiments/       # 주차별 실험 기록 (아래 템플릿)
    ├── adr/               # 설계 결정 기록
    ├── learning/          # 배운 것 정리 (개발자가 쓰고 Claude가 리뷰, 복기용)
    └── infra/             # 인프라 구성 노트 (하드웨어, 네트워크, 클러스터, 트러블슈팅)
```

## 5. 로드맵과 현재 진행 상황

**현재: Phase 2 / 8주 차** (노드 확장·컨트롤 플레인 HA는 진행 중, 백로그) ← 주차가 끝날 때마다 이 줄을 갱신한다.

> 2026-11-11 변경: 1주 차에 인프라 구축을 넣고 이후 일정을 1주씩 미뤘다 (총 19주 → 2026-11-12 3주 차를 2주로 늘려 총 20주). 이유: 이후 모든 단계가 클러스터를 전제로 하고, 온프레미스 k8s는 불확실성이 가장 커서 먼저 해소한다.

### Phase 1. 뼈대 (1~4주)
- [x] 1주: 인프라 — Terraform으로 Proxmox VM 프로비저닝, k8s 클러스터, 스토리지 클래스, LoadBalancer·Ingress, Argo CD 설치. 완료 조건: Git push → Argo CD 동기화 → 브라우저에서 hello 앱 접속. (Kafka·관측성·Chaos Mesh는 필요한 주차에 설치)
- [x] 2주: 송금 유스케이스·서비스 경계 정리(ADR), 멀티모듈 레포 생성, `core` 서비스(account 모듈부터)를 Argo CD로 클러스터에 배포
  - [x] ADR-0001 승인 (2026-11-11): 코어 모놀리스 + 주변 MSA. 면접 30초 답변은 개발자가 작성 예정
  - 복습 완료 (2026-11-11): MetalLB / kube-proxy / flannel / Traefik 역할 분담. X-Forwarded-For 부분은 보류 (아래 백로그)
  - [x] 배포 파이프라인 완료 (2026-11-12): push → CI(테스트·amd64 이미지·GHCR·태그 커밋) → Argo CD → `http://core.172.30.1.240.nip.io/actuator/health` UP
  - [ ] 개발자 티켓 남음: core `application.yaml`에 프로브·actuator 노출·graceful shutdown을 명시 (Boot 4 기본값 확인 후 명시) → 3주 차 시작 전에
- [x] 3주 (**2주로 늘림**, 2026-11-12, 완료 2026-11-14): 아래 3-1, 3-2
  - 이유: 2주 차는 배포 파이프라인까지만 해서 core에 도메인·DB가 없었다. "돈이 움직이는 트랜잭션"은 이 프로젝트의 심장이라 서두르지 않는다. 이후 주차 번호는 문서 곳곳에서 참조하므로 **바꾸지 않고**, 3주 차만 2주로 쓴다 (총 20주)
  - [x] 3-1주: PostgreSQL(CloudNativePG), `core` account 모듈, `ledger` 모듈 + 송금 처리 API(로컬 트랜잭션, 송금 ID 멱등, 상태 조회), 입출금 API
    - [x] CloudNativePG 오퍼레이터 + `core-db` (PostgreSQL 18.6) (2026-11-12)
    - [x] 티켓 A: account 모듈 — 계좌 개설·조회 API, 입출금 도메인 규칙, 업무 예외, 단위·리포지토리 테스트 13개. 클러스터에서 Flyway V1 적용·개설·조회 확인 (2026-11-12)
    - [x] 티켓 B: ledger 모듈 + 거래 처리(입금·출금·당행 송금). posting 모듈이 account·ledger를 한 트랜잭션으로 지휘, 멱등·동시 중복 요청·롤백 통합 테스트, 값 객체(`shared`) (2026-11-13)
    - [x] 티켓 C: 거래 HTTP API `/postings` (Idempotency-Key 헤더, 201/200, 상태 조회는 200 + `COMPLETED`/`NOT_PROCESSED`) + 예외 매핑. 클러스터에서 송금·재요청·조회·400/409/422 curl 확인 (2026-11-13)
    - [ ] 오늘 배운 것 정리 (`docs/learning/2026-11-13.md`, 개발자 작성 → Claude 리뷰) → 다음 세션 첫 순서
  - [x] 3-2주: `transfer` 추가(헥사고날, `transfer → core` 동기 REST, 상태 머신), `gateway` 라우팅 + JWT 인증 필터(인증은 gateway, 인가는 서비스)
    - [x] 송금 도메인: 생성 팩토리, 상태 전이 규칙 (2026-11-13)
    - [x] core 호출 어댑터: 응답 → 완료·거절·결과 불명 분류, 타임아웃, 어댑터 테스트 14개 (2026-11-13)
    - [x] T-1: 송금 요청 use case (트랜잭션 분리: 처리중 커밋 → 트랜잭션 밖 core 호출 → 결과 커밋), 입구 검증 400, 송금 API. 클러스터에서 transfer → core 송금·거절·400 curl 확인 (2026-11-14)
    - [ ] 오늘 배운 것 정리 (`docs/learning/2026-11-14.md`, Claude 공부 자료 작성 완료 → 개발자 요약 → Claude 리뷰)
    - [x] T-2: 고객 멱등 키 (`Idempotency-Key` 헤더, Flyway V2). 메커니즘은 core 재사용, 설계 초점은 **처리 중(PROCESSING) 중복 요청 응답**, **UNKNOWN 뒤 같은 키 재시도**, **키 범위(사용자별)**
      - 2026-11-14 결정: 8주 차에서 앞당김. 멱등 메커니즘(UNIQUE + 재시도)은 core에서 이미 재현·증명해 다시 재현할 가치가 낮고, transfer에서만 새로 생기는 문제(원격 호출로 처리 중 상태가 보임)에 집중한다
      - 2026-11-14 설계: 송금 행에 고객 키 컬럼 + UNIQUE, 재요청엔 **송금의 현재 상태**를 돌려준다 (응답을 따로 저장하는 Stripe 방식 아님 → 키가 송금과 같은 INSERT라 원자성이 자연히 보장되고, 해소된 결과가 최신으로 보인다). gateway가 아니라 transfer에서 처리하는 이유도 이것 (gateway는 업무를 몰라 응답 저장 방식밖에 못 씀)
        - 같은 키: COMPLETED·FAILED·PROCESSING → 그대로 200 / UNKNOWN → 같은 송금 ID로 core 재전송 후 200 (또 모름이면 UNKNOWN 유지) / 내용 다름 → 409 / 헤더 없음 → 400 / 처음 → 201
        - 키 범위: 일단 전역 UNIQUE, gateway JWT 인증이 생기면 사용자별로
      - 완료 (2026-11-14): 테스트 56개, 클러스터에서 V2 적용(기존 송금 3건 legacy- 키로 채움)·같은 키 201→200·잔액 한 번만·409·400 확인
    - [x] gateway (ADR-0002, 2026-11-14): 3주 차 안에서 끝내기로 결정 (4주 차로 미루지 않음)
      - [x] G-1: 모듈 + `/transfers/**` 라우팅 (Spring Cloud 2025.1.4), core `/postings`는 열지 않음, 다운스트림 타임아웃 5s
      - [x] G-2: JWT 인증 — Security Resource Server로 검증(HS256 고정, iss·exp), 학습용 `POST /auth/token`(15분), `X-User-Id` 덮어쓰기 필터. 위조 헤더를 먼저 재현. 비밀키는 k8s Secret
      - [x] G-3: core·transfer Ingress 닫기 (외부 진입점은 gateway 하나)
      - [x] 멱등 키 사용자별 (transfer Flyway V3, `(requester_id, idempotency_key)` UNIQUE, 기존 행 `legacy`). 전역 키의 정보 누출을 클러스터에서 먼저 재현
      - [x] G-4: 계좌 소유자 인가 (BOLA) — core가 출금 트랜잭션 안에서 검사(멱등 판정 뒤), 남의 계좌는 없는 계좌와 같은 404, 계좌 개설·조회를 gateway 뒤로(`/accounts/**`, `X-User-Id`), transfer → core 요청자 전파. BOLA를 먼저 재현
- [x] 4주: Strimzi로 Kafka 구축, 송금 완료 이벤트를 `notification`이 구독 (완료 2026-11-18)
  - [x] ADR-0003 (2026-11-18): 발행은 transfer, 끝 상태로 바뀌는 순간만, 키 = 송금 ID, 스키마는 `libs/common` 공유(타협), notification 전용 DB로 멱등, 발송은 가짜(최소)
  - [x] K-1: Strimzi 1.3.1 + Kafka 4.3.2(KRaft 1대) + 토픽 `transfer-events`(파티션 3) 매니페스트 (클러스터 꺼져 있어 미적용)
  - [x] K-2: `TransferFinishedEvent`, transfer 발행(커밋 뒤 바로 발행 = 6주 차 유실 구멍, 의도), `max.block.ms` 1초(pitfalls 20). 테스트 68개 (진짜 Kafka 포함)
  - [x] K-3: notification 서비스 (PENDING INSERT → 트랜잭션 밖 발송 → SENT, `event_id` UNIQUE 멱등, poison pill 건너뜀, 일시 오류 재시도). 테스트 6개
  - [x] K-4: 클러스터에서 송금 완료·잔액 부족 → transfer 발행 → notification SENT, 재요청은 발행 안 됨, 컨슈머 그룹 랙 0 확인 (2026-11-18)

### Phase 2. 정합성 (5~8주) — 끝나면 이력서에 추가
- [x] 5주: 타행 이체를 오케스트레이션 사가로 구현 (코어 출금 → `bank-mock` 송금 → 실패 시 출금 보상, `bank-mock`은 단순 성공/실패), 코레오그래피와 비교 정리
  - [x] 설계 연습 04 + ADR-0004 초안 (2026-11-19): 지휘자 transfer, **피벗 = 외부 송금 성공**(앞은 보상, 뒤는 재시도), WITHDRAWN / INTERBANK_REQUESTED 분리(보냈을 수도 있으면 보상 금지), FAILED ≠ CANCELLED, 보상 키 `{송금ID}-cancel`(결정적), 미정산 계정 지금, `POST /transfers` + `toBankCode`, bank-mock은 수취 계좌 규칙 + 거절률 스위치
  - 2026-11-19 결정: 고객 응답은 **이번엔 동기**, 비동기·혼합(N초 대기 후 "처리 중")은 8주 차 해소 스케줄러와 함께 보강. 15주 차 외부 지연 실험의 "전" 상태
  - [x] S-1: `bank-mock` 서비스 (송금 API, 같은 키 멱등, 수취 계좌 규칙 거절 + 거절률 설정값) + 배포. 거절도 200(판정 확실), 저장은 메모리. 클러스터 확인 (2026-11-19)
  - [x] S-2: core 미정산 계정(Flyway V3) + 타행 출금 / 출금 취소(역분개) API (2026-11-19). 취소는 원래 출금을 가리키고 금액·계좌는 원래 전표에서, `reverses_entry_id` UNIQUE로 한 번만(자연 키, 개발자 결정). 테스트 11개 추가
  - [x] S-3: transfer 타행 사가. **2026-11-19 분담: 사가 로직은 개발자가 직접 구현** (핵심 로직)
    - [x] 배관 (Claude): Flyway V4 "공통 + 상세"(`transfer`에 kind·to_account_id NULL 허용·사가 상태, `interbank_transfer` 상세 테이블. 고객 멱등 키 UNIQUE가 공통 테이블에 있어 당행·타행 통틀어 유지), `CoreInterbankPort`·`ExternalBankPort` + 어댑터(외부 409·5xx·타임아웃은 Unknown = 보상 금지, 취소에는 Rejected 없음), notification CANCELLED 알림 + 이벤트 계약 넓힘(소비자 먼저, pitfalls 18)
    - [x] 사가 구현 (2026-11-20): 상태 머신·사가 지휘는 개발자, 저장 어댑터·API·발행 배관·테스트·리팩터링은 Claude. 테스트 119개
      - 상태 확정 (V5): 원격 호출마다 의도 상태 하나, 결과 불명은 의도 상태에 남는다 (pitfalls 27). `WITHDRAW_REQUESTED → WITHDRAWN → EXTERNAL_BANK_REQUESTED → COMPLETED`, 거절은 `FAILED` / `EXTERNAL_BANK_REJECTED`(= 보상 의도) `→ CANCELLED`
      - 재시도: 출금·보상은 자기 자신으로 재시도 가능(멱등 보장), 외부 송금은 재전송 대신 8주 차 결과 조회
      - `BankCode` 값 객체 (enum 아님: 기관 코드는 바뀌는 기준 정보), 우리 은행 코드 999 가정
      - 당행·타행 같은 멱등 키 → 원인이 적힌 409 (pitfalls 28)
  - [x] S-4 (2026-11-20): gateway 경유 성공(미정산 3만 남음 = 차액결제 없음, 정상) / 거절 → 보상(출금+취소 전표, 잔액·미정산 원복, CANCELLED 알림) / 재요청 200 확인. 코레오그래피 비교(흐름은 개발자 설명)와 실험 기록 `docs/experiments/week-05-saga.md`
- [x] 6주: 커밋과 발행 사이 장애로 이벤트 유실 재현 → Outbox로 해결, 커밋 직전 컨슈머 종료로 중복 수신 재현 → 멱등 컨슈머로 해결 (완료 2026-11-21, `docs/experiments/week-06-outbox.md`)
  - 2026-11-21 결정: 릴레이는 **폴링**(transfer 안 `@Scheduled`, CDC는 비교만) + **`FOR UPDATE SKIP LOCKED`**(ShedLock 아님) + 발행 표시 후 **일일 정리**(7일, 락 없음 = 멱등). 발행 포트는 그대로, 구현만 outbox INSERT로, 포트 계약은 "실패하면 송금과 함께 롤백"으로 뒤집음. eventId = outbox 행 ID (INSERT 때 한 번)
  - 분담: outbox 발행기·유스케이스 트랜잭션 이동(당행·타행)은 개발자, 테이블·모델·릴레이·정리·테스트는 Claude. 테스트 127개
  - 유실 재현은 테스트(`TransferEventLossTest`, docker pause), 해결 확인은 클러스터(Kafka 브로커 파드 삭제 31초 동안 송금 5건 → 복구 3초 안에 5/5 전달)
  - 중복 수신: 멱등 컨슈머는 4주 차 K-3에서 이미 구현 → 이번엔 클러스터에서 오프셋 되감기로 재현, 5건 모두 DUPLICATE, 알림 행 수 불변
  - 배운 함정: pitfalls 29 (발행 타임아웃 ≠ 미발행), 30 (dual write는 순서로 결과를 고른다), 31 (GitOps selfHeal이 손댄 장애를 되돌림)
  - 남은 것: outbox 미발행 수·가장 오래된 미발행 나이 지표 (13주 차 체류 시간 지표와 함께)
- [x] 7주: 동시 출금으로 잔액 깨짐 재현, 낙관적 락·비관적 락·Redis 분산 락을 k6로 비교 (측정 완료 2026-11-23, `docs/experiments/week-07-locks.md`)
  - [x] k6 (2026-11-23): 클러스터 안 k6 Job 실행기(`load-test/lock-compare.sh`), 시나리오 hot(계좌 1개 → 200개)·spread(200개끼리), 회차마다 새 계좌, DB 정합성(잔액 vs 원장)·Redis 명령 종류별 기록. 전략 6개 × (spread 200 + hot 150/300/500건/초)
    - 1차 hot 50건/초는 균일 도착이라 경합이 안 생겨 NONE도 안 깨짐 → 다시 (pitfalls 36)
    - 결과: NONE은 150건/초부터 잔액 깨짐(500건/초 +8,507원, 원장은 정확), ATOMIC_UPDATE는 500건/초도 p99 7ms, PESSIMISTIC은 느린 성공(p50 1.26s), OPTIMISTIC은 실패 39%(500), **REDIS 계열은 300건/초부터 core OOMKilled**
    - 분산 락 기본 요금: 락 한 번 왕복 4번·명령 13개 (jar 확인) → 풀기 전 `HEXISTS` 확인 제거 (개발자 결정, TOCTOU라 막지도 못했음)
  - [x] 판독 1(REDIS 붕괴: 처리 한계가 낮아 먼저 줄 → 대기자마다 Netty direct 버퍼 → OOMKilled, 개발자가 Grafana로 직접) · **기본 전략 ATOMIC_UPDATE** (개발자 결정: 지금 규칙은 전부 행 하나 안의 조건, 락은 작업마다 고른다) (2026-11-23). 판독 2~5는 20주 차 뒤 블로그 때
  - [ ] **동시성 판단 공부 (2026-11-22 개발자 요청, 중요):** `docs/learning/concurrency.md`. 목표는 "이 상황이면 뭘 쓰나"에 고려할 것 → 선택 → 트레이드오프로 답하기. Claude 공부 자료 완료 → 개발자 요약 → Claude 리뷰. 분산 락 절과 비교표 수치는 Redis 구현·k6 뒤에 채움
  - 곁다리 실습 (2026-11-21 개발자 요청, 30분): 로컬 Docker MySQL 8로 갭 락·넥스트키 락 재현 (세션 두 개, 범위 잠금 읽기 중 범위 밖 INSERT 대기, 인덱스 없는 잠금 읽기 = 사실상 테이블 잠금, `performance_schema.data_locks`로 확인) → 같은 실험을 PostgreSQL에서 (갭 락 없음, 반환된 행만 잠금). 계기: Outbox 릴레이 SKIP LOCKED의 MySQL 함정
- [ ] 8주: 타행 이체에 미정산 계정·타임아웃·결과 불명 추가, 상태 조회로 결과 불명 해소(해소 스케줄러), 일일 대사 배치 (+ 이자 일배치와 실시간 송금 경합 검토). 고객 멱등 키는 3-2주 T-2로 앞당김 (2026-11-14)

### Phase 3. 관측성 (9~11주)
> 2026-11-24 개발자 결정: **배관(설치·연결·문맥 전파·대시보드·알림 규칙)은 Claude가 설계 퀴즈 없이 빠르게** 하고, 개발자의 시간은 **실무에서 모니터링을 어떻게 쓰나**(상황 → 신호 → 클릭 경로 → 판단)에 쓴다. 활용법은 `docs/learning/monitoring-playbook.md`에 장면마다 쌓는다. 주차마다 판독 연습(장애를 걸고 플레이북 순서대로)을 하나 이상 넣는다.
- [ ] 9주: OpenTelemetry로 gateway → 서비스 → Kafka → 컨슈머까지 하나의 트레이스로 연결 (Tempo)
  - [x] 설치 (2026-11-23): Tempo · OTel Collector(잡음 필터) · 에이전트(공통 차트 init 컨테이너), Grafana 데이터 소스 (`docs/infra/README.md` 트레이싱)
  - [x] Outbox 잇기 (2026-11-24): 행에 `traceparent`(V7), 릴레이가 행마다 요청의 자식 구간 → 송금 한 건 = gateway부터 notification까지 한 트레이스 (pitfalls 39)
  - [x] 잡음 정리 (2026-11-24): Collector 꼬리 기반 샘플링으로 릴레이 회차 트레이스(1초마다) 통째로 버림
  - [ ] 남음: 판독 연습 1회 (bank-mock 지연 → 느린 송금 트레이스 읽기, 플레이북 1-1). 로그에 traceId는 10주 차 Loki와 함께로 이동
- [ ] 10주: Prometheus·Loki·Grafana로 RED 지표, 컨슈머 랙, JVM 대시보드, 로그 ↔ 트레이스 연결
  - 2026-11-18 결정: **기본 스택만 앞당긴다** (노드 확장 전에). `kube-prometheus-stack`(Prometheus·Grafana, 노드·쿠버네티스 기본 대시보드) + Strimzi·CloudNativePG 대시보드 + etcd 지표 + 앱 지표(`micrometer-registry-prometheus`, 2026-11-18 승인: JVM·API별 지연·커넥션 풀). 이유: 노드 확장·호스트 다운·k6 기준선을 그래프로 보면서 하려고, 같은 대시보드를 일찍부터 반복해 봐야 평소 모양이 눈에 익는다. 서비스별 RED 대시보드 설계, Loki·Tempo, SLO·알림은 10~11주 차 그대로
- [ ] 11주: 송금 성공률·p99 SLO 정의, Alertmanager 알림

### Phase 4. 장애 실험 컨트롤 패널 (12~13주) — 끝나면 시연 가능
- [ ] 12주: k6-operator·Chaos Mesh 설치, `chaos-control`이 TestRun·PodChaos·NetworkChaos 생성, 각 서비스에 실험 스위치 API(락 전략, 멱등 처리, 서킷 브레이커 on/off), RBAC으로 실험 네임스페이스만 접근
- [ ] 13주: 화면 목업 확정 후 `web` 구현 — 시나리오 버튼·스위치, SSE 실시간 지표(TPS, p99, 에러율, 컨슈머 랙, 파드 상태), Grafana 임베드, 잔액 정합성 지표. Phase 2 시나리오(동시 출금, 중복 메시지)를 버튼으로 이전

### Phase 5. 장애와 성능 (14~17주) — 실험마다 패널에 버튼 추가
- [ ] 14주: 게이트웨이를 MVC / MVC + 가상 스레드 / WebFlux로 두고 느린 다운스트림에서 k6 비교, 이벤트 루프 블로킹 재현 (패널: 게이트웨이 모드 스위치)
- [ ] 15주: 외부 은행 지연으로 장애 전파 재현 → 타임아웃·서킷 브레이커·벌크헤드, 재시도 폭주를 백오프+지터로 차단 (패널: "외부 은행 3초 지연")
- [ ] 16주: 컨슈머 랙·핫 파티션·리밸런싱 반복 재현 및 해결, KEDA 랙 기반 오토스케일 (패널: "핫 파티션", "컨슈머 죽이기")
- [ ] 17주: 카오스 테스트(파드 강제 종료, Redis 페일오버, DB 다운)에서 송금 정합성 확인, graceful shutdown·readiness 프로브 (패널: 장애 종류별 버튼)

### Phase 6. 정리 (18~19주)
- [ ] 18주: README에 아키텍처·실험 결과 정리, 컨트롤 패널로 장애→회복을 보여주는 2분 시연 영상(GIF), 이력서 업데이트
- [ ] 19주: 버퍼 / 패널을 화면 공유하며 설명하는 모의 면접

### 백로그 (여유 있는 주에 끼워 넣기)
- [ ] **설계 심화 트랙 (2026-11-14 추가, 상시):** 목표는 분산 흐름 설계를 "겉핥기"에서 "능숙함"으로. 개발자 요청
  - **종이 설계 연습** `docs/design-drills/`: 주 1문제, 코드 없이 30분. 개발자가 템플릿(홉 분석표 · 상태 머신 · 장애 시나리오 · 중복 · 대사)을 채우고 Claude가 장애 질문으로 리뷰. 00(예시) → 01 주문 결제 → **04 타행 이체(5주 차 전에, ADR-0004 초안)** → 02 → 03 → 05 → 06 → 07
  - **주차별 심화 예비 재료** (해당 주차에 여유가 있으면 끼워 넣기):
    - 5주: 사가를 **설계 먼저** (04번 연습 → ADR-0004), 보상 실패·보상 멱등, 오케스트레이션 vs 코레오그래피를 같은 흐름으로 둘 다 그려 비교. 워크플로 엔진(Temporal)과 손구현 사가 비교는 정리만 (도입하지 않음)
    - 6주: Outbox 발행 방식 비교 (폴링 vs CDC), 멱등 컨슈머의 처리 기록 보관 기간
    - 8주: **상태 변경 이력 테이블**(거래 하나의 타임라인), **에이징 리포트**(PROCESSING·UNKNOWN 체류 시간), 대사 불일치 분류(우리만 / 상대만 / 금액 다름)와 **수작업 조정 전표**(작성자·승인자 분리, 감사 로그), 해소 스케줄러와 고객 재시도의 경합(낙관적 락)
    - 13주: 패널에 **PROCESSING·UNKNOWN 체류 시간 지표** → 카오스 때 멈춘 송금이 쌓였다 해소되는 장면
    - 19주: 시스템 설계 모의 면접 (design-drills 05~07 중 하나를 화이트보드로)
- [ ] **Flyway 복습 (다음 세션 첫 순서, 2026-11-14 실습 일부 완료: transfer V2 적용·`flyway_schema_history` 확인, search_path 장애 경험 → 개념 정리만 남음):** 번호 붙은 SQL을 순서대로 한 번씩만 실행, `flyway_schema_history` 기록 테이블, 이미 적용된 파일을 고치면 안 되는 이유(체크섬). 실습: 클러스터 DB에서 기록 테이블 조회 → V2 추가해 push → 적용 확인
- [ ] **스토리지 복습 (PVC · PV · 마운트, CloudNativePG):** PVC(요청서) → PV(실제 디스크) → volumeMounts(컨테이너 경로) 연결, local-path가 노드에 묶이는 한계, 오퍼레이터가 대신 만들어 주는 것. 실습: DB 파드 삭제 후 데이터 유지 확인, DB가 올라간 노드 끄기. 재료: `deploy/platform/core-db/cluster.yaml`, `kubectl -n bank get pvc,pv`. 늦어도 17주 차(DB 다운 실험) 전에
- [ ] **배포 파이프라인 복습 (Dockerfile · CI · 공통 Helm 차트):** Dockerfile 최적화(레이어 분리로 코드만 바뀌면 수십 KB만 전송, 멀티스테이지, 비root), CI 워크플로 세부(캐시, `concurrency`, amd64 지정, 태그 커밋 충돌 처리), 그리고 차트: JVM 메모리(`MaxRAMPercentage`, 왜 CPU limit을 안 거나), 프로브 3종(startup·liveness·readiness), 무중단 롤링(`maxSurge`/`maxUnavailable`)과 종료 순서(preStop → SIGTERM → graceful shutdown). 재료: `docs/infra/README.md` 배포 구조 섹션, `deploy/helm/spring-service/`. 직접 파드를 죽이고 롤링하면서 확인하는 방식으로. 늦어도 17주 차(graceful shutdown·카오스) 전에
- [ ] **노드 확장 (2026-11-14 추가, 9주 차 관측성 스택 전에):** 집에 남는 PC(인텔, 32GB, Windows·Linux 멀티부팅, Proxmox 없음)를 클러스터에 추가. 2026-11-14 실측: 워커 3대 메모리 사용 13~21%라 4주 차 Kafka는 기존 클러스터로 충분 ("Kafka 전에 확장"은 실측 없이 한 판단이라 정정). 확장 이유는 관측성 스택 메모리 + 물리 호스트 2대(17주 차 "호스트 통째로 다운" 재현). 방식: Windows를 유지해야 하면 Linux에 k3s agent 직접 설치, PC를 실험 전용으로 내줄 수 있으면 Proxmox(독립 호스트로, Proxmox 클러스터로 묶지 않음 — 2대는 quorum 문제). **2026-11-14 결정: Proxmox로 (디스크 전체, Windows·Ubuntu 삭제).** 설치는 개발자가 `docs/infra/runbooks/proxmox2-install.md`대로, 이후는 Claude
- [x] **노드 확장 1단계 완료 (2026-11-18):** proxmox2에 워커 2대(bank-w4·w5, 10 vCPU / 12GB), 모든 노드에 `topology.kubernetes.io/zone` 라벨. 다음은 컨트롤 플레인 HA (별도 세션)
- [ ] **Terraform 복습 (선택, 시간 남으면, 2026-11-18 개발자 결정: 핵심 아님):** 모듈과 프로바이더 alias, `moved` 블록, "생성 때만 쓰는 값"이 바뀌면 교체(replace)되는 이유. 꼭 가져갈 것은 하나: apply 전에 plan을 읽고 destroy/replace가 있으면 멈춘다 (pitfalls 25). 재료: `infra/terraform/main.tf`, `modules/proxmox-host/`
- [ ] **외부 공개: Cloudflare Tunnel (2026-11-18 추가, 13주 차 패널 전):** 공유기 포트를 열지 않고 gateway·패널을 인터넷에 공개 (이력서 소재). 필요: 도메인(연 1~2만 원), Cloudflare 계정, 클러스터에 `cloudflared`. **공개 전 필수:** `/auth/token`(비밀번호 없음)과 패널은 Cloudflare Access 뒤로. 용도는 기능·시연·실제 인터넷 경로 확인(HTTPS, `CF-Connecting-IP`로 고객 IP → 리눅스 네트워크 딥다이브와 연결). **부하 테스트는 터널로 하지 않는다** (무료 플랜 레이트 리밋·약관, 인터넷 잡음이 실험 전후 차이를 흐림) → 부하는 클러스터 안 k6-operator, 느린 외부망은 Chaos Mesh 지연·손실 주입
- [x] **Tailscale (2026-11-18 추가, 2026-11-23 완료: bank-cp3 서브넷 라우터, WOL은 항상 bank-cp3가 보냄, 밖에서 확인은 남음 → `docs/infra/README.md` 원격 접속):** 노트북 `bank-cp3`를 서브넷 라우터(`172.30.1.0/24`) + WOL 발신기로. 포트 개방 없음 (WireGuard + NAT 홀 펀칭). 개발자 계정 로그인 필요
- [ ] **관측성 감각 트랙 (2026-11-17 추가, 개발자 요청):** 대시보드·APM에 익숙해지기. `docs/learning/observability.md`. 실험 기록마다 "대시보드 판독" 칸(먼저 꺾인 그래프 / 피해자). 로드맵에 없는 재현 후보를 패널 버튼으로: 느린 쿼리(인덱스 유무), 메모리 누수(톱니 바닥 상승), CPU 스로틀링(limit을 일부러 걸기). k6는 `constant-arrival-rate` 기본 (coordinated omission 회피)
- [ ] **컨트롤 플레인 HA (2026-11-17 추가, 늦어도 17주 차 전):** 지금 k3s 서버 1대(SQLite) = 단일 장애점. 물리 호스트 2대로는 과반수 HA 불가 → 세 번째 장애 영역으로 **집에 있던 노트북 Samsung NT455R4J (AMD A6 4코어, 2015년쯤)**를 쓴다 (2026-11-17 결정: 파이·중고 미니 PC·새 N100 비교 끝에 0원. 중재자는 성능보다 상시 가동이 중요, 배터리가 UPS 역할). k3s 서버 3대 + 내장 etcd (기존 서버 / proxmox2 / 노트북), 노트북은 컨트롤 플레인 위주(taint). HDD면 일단 쓰고 리더 선출이 잦으면 SATA SSD로 교체. Ubuntu Server, 덮개 닫아도 켜짐, `172.30.1.202`. 항상 켜 두는 WOL 발신기·Tailscale 역할도 겸함 (`docs/infra/README.md` TODO "집 밖에서 켜고 끄기"). 공부 자료 `docs/learning/consensus.md`
- [ ] **플랫폼 엔지니어링 트랙 (2026-11-17 추가, 19주 차 버퍼 이후):** 오퍼레이터 = 관리형 서비스 (CloudNativePG ≈ RDS, Strimzi ≈ MSK, Redis 오퍼레이터 ≈ ElastiCache). 그 위에 Crossplane으로 `kind: Database` 같은 셀프 서비스 API, Backstage로 콘솔. 오퍼레이터를 추가할 때마다 "AWS의 무엇에 해당하나"를 같이 정리
- [ ] **인증 서버 분리 (2026-11-14 추가, 노드 확장 뒤):** gateway 학습용 발급(HS256) → Keycloak(RS256 + JWKS). 키 교체(`kid`), 토큰 폐기(짧은 TTL + 리프레시, 블랙리스트), **인증 서버 장애 실험**(로컬 검증이라 기존 사용자 송금은 계속됨 vs introspection 방식은 단일 장애점) → 패널 버튼 "인증 서버 죽이기"
- [ ] **NetworkPolicy · 제로 트러스트 (2026-11-14 추가):** gateway 뒤 서비스가 `X-User-Id` 헤더를 그대로 믿는 문제(파드 하나가 뚫리면 횡적 이동으로 위조 가능). 선택지: NetworkPolicy로 "transfer는 gateway에서만" / 서비스도 JWT 재검증. 위조 요청을 먼저 재현한 뒤 막는다
- [x] **CI 변경 감지 (2026-11-14 추가, 2026-11-19 완료: `.github/scripts/changed-services.sh` + 판정 job → 동적 matrix, pitfalls 14):** 지금은 matrix가 고정이라 push마다 모든 서비스를 다시 빌드하고 태그를 갈아 끼워, 안 바뀐 서비스도 롤링 재시작된다. 바뀐 경로(`services/<서비스>/**`)만 빌드하고, 공통 경로(`build-logic`, `gradle`, Dockerfile, 워크플로)가 바뀌면 전부 빌드. 서비스가 4개 넘어가기 전에
- [ ] **리눅스 네트워크 딥다이브:** netfilter 처리 단계, DNAT/SNAT, conntrack을 밑에서부터 → 이어서 X-Forwarded-For와 고객 IP 유실, `externalTrafficPolicy: Local` 전후 실측, Spring `forward-headers-strategy`, 헤더 위조. 재료: `docs/infra/notes/k8s-service-networking.md`. 늦어도 3주 차 `gateway`에서 고객 IP를 로그에 남기기 전, 또는 19주 차 버퍼에

Phase 1~3에서는 k6를 CLI로 돌리되, **나중에 패널 버튼으로 옮길 것을 전제로** 시나리오를 `load-test/`에 하나씩 분리해 둔다. 실험 스위치도 처음부터 설정값으로 바꿀 수 있게 만든다 (하드코딩 금지).

## 6. Claude가 일하는 방식

이 프로젝트는 **개발자가 배우는 것**이 목적이다. 코드를 빨리 많이 만드는 것보다 개발자가 이해하고 설명할 수 있는 것이 우선이다.

**역할 분담 (2026-11-12 변경):** **개발자가 설계 방향을 정하고, Claude가 구현한다.** 실무의 AI 협업 방식과 같고 속도가 난다.
순서: Claude가 티켓과 설계 질문(힌트) → 개발자가 방향 결정 (Claude는 리뷰·대안 제시) → Claude 구현 → 핵심 포인트를 설명해 개발자가 면접에서 모든 줄을 방어할 수 있게 한다.
핵심 로직(사가, Outbox, 락, 멱등)은 방향이 정해지기 전에 먼저 짜지 않는다. 합의한 범위 밖의 코드는 짜지 않는다.
(이전: 인프라 외 코드는 개발자가 직접 짜고 Claude는 지시한 부분만 구현)
**예외 — 인프라:** `infra/`(Terraform, 스크립트), `deploy/`(Dockerfile, Helm, Argo CD, k8s 매니페스트), `.github/`(CI 워크플로)는 Claude가 직접 작성한다. 대신 결정 이유와 실무 포인트를 짧게 설명하고 `docs/infra/`에 기록한다.

1. **작업 전에 계획부터.** 이번 세션에서 할 일을 3~5단계로 제안하고 동의를 받은 뒤 시작한다. 계획은 진행하면서 바뀔 수 있다.
2. **티켓 형식.** 배경(실무에서 왜 중요한가) / 요구사항 / 완료 조건 / 생각해 볼 질문.
3. **실무 가치를 항상 밝힌다.** 각 작업이 실제 현업에서 어떤 사고·문제로 이어지는지 설명한다. 실무와 거리가 있거나 면접 가치가 낮은 부분은 솔직하게 말한다.
3-1. **핵심 로직은 설명과 함께.** 사가, Outbox, 락, 멱등 처리 같은 핵심 부분은 왜 그렇게 짜는지, 다른 선택지는 무엇인지 설명한다. 정답을 바로 주기보다 힌트부터 준다.
3-2. **DDD 용어를 짚어 준다.** 티켓·리뷰에서 해당하는 DDD 개념이 나오면 "DDD 용어로는 ○○"라고 연결한다 (바운디드 컨텍스트, 애그리거트, ACL 등). 이 프로젝트는 DDD 프로젝트가 아니므로 과장하지 않는다.
4. **문제를 먼저 재현한다.** 해결책을 넣기 전에 문제가 실제로 일어나는 테스트나 k6 시나리오를 먼저 만든다. 재현이 안 되면 해결도 증명할 수 없다.
5. **모르면 추측하지 않는다.** 클러스터 상태, 버전, 설정을 모르면 확인 명령을 제안하거나 묻는다.
6. **작게 커밋한다.** 한 커밋에 한 가지 변경. 커밋 메시지는 한국어로 `[서비스] 무엇을 왜` 형식.
7. **세션 끝에 정리한다.** 무엇을 했는지, 다음에 할 일, 실험 기록에 남길 내용을 3줄로 요약하고, 주차가 끝났으면 5장의 체크박스와 "현재" 줄을 갱신한다. **요약은 채팅으로 끝내지 않고 `docs/worklog.md`에 남긴다** (한 일 / 결정 / 버그·트러블슈팅, 맨 아래 "이력서 소재" 갱신). 이력서 개인 프로젝트란의 원재료다.
7-1. **배운 것은 개발자가 직접 정리한다 (2026-11-13). 단, 2026-11-23부터 자기 말 요약은 20주 차 뒤 몰아서** (주제가 주차를 건너 이어져 블로그를 줄기로 엮으려고). Claude는 공부 자료를 계속 쓰고, 세션마다 새 장면을 `docs/learning/story-arcs.md`의 해당 줄기에 붙인다. 요약을 남은 일로 재촉하지 않는다. 세션에서 새로 배운 개념은 `docs/learning/`에 개발자가 자기 말로 쓰고, Claude는 틀린 부분·빠진 부분·면접에서 나올 꼬리 질문을 리뷰한다. 세션 끝에 Claude는 날짜 파일에 아이템별 **💡 핵심 / 🎤 면접 꼬리 질문 / 📎 정답지 링크(기존 문서·코드)**를 달아 공부 자료로 만들고, 개발자는 그걸로 공부한 뒤 자기 말로 요약을 쓴다 (2026-11-13 변경: 처음엔 질문만 남겼으나 개발자 요청으로 공부 자료 형태로). 진행 호흡은 주차 속도보다 정리·복기를 우선한다.
7-1-0. **기술 질답은 그 세션 안에 기록한다 (2026-11-22, 개발자 요청).** 세션 중 개발자가 던진 기술 질문과 답(특히 "정확히 어떻게 동작하나" 같은 깊은 질문)은 채팅으로 끝내지 않고 주제별 `docs/learning/<주제>.md`에 💡🎤📎 형식으로 남긴다 (예: `concurrency.md`, `io-and-event-loop.md`). 라이브러리 내부 동작은 가능하면 소스·jar로 확인하고 확인했다고 표시한다. 이유: 나중에 질문하거나 복기할 때 다시 떠올리려고. 개발자가 "저장해 둬"라고 하면 `/save-qa` 스킬로 질문 은행(`docs/learning/question-bank/`)에 면접·상황 적용·장애 질문 형태로도 남기고, 복습은 `/quiz-me` (프로젝트를 마친 뒤 무작위 출제·답변 연습 계획)
7-1-1. **실무 함정은 따로 모은다 (2026-11-14).** 세션 중 "여기서 실무 사고가 난다"는 지점(BOLA, TOCTOU, 타임아웃 계층 등)을 짚으면, 그 자리에서 `docs/learning/pitfalls.md`에 한 항목(장면 / 함정 / 원칙 / 🎤 / 📎)을 추가한다. 개발자 요청: 이 감각이 가장 도움이 된다.
7-2. **핵심 흐름은 설계 먼저 (2026-11-14).** 사가·Outbox·결과 불명 해소·대사처럼 여러 홉을 건너는 흐름은, Claude가 티켓을 주기 전에 개발자가 홉 분석표와 상태 머신 초안을 먼저 그린다 (`docs/design-drills/` 템플릿). Claude는 정답 대신 장애 질문("2단계 성공 후 3단계 타임아웃이면?")으로 리뷰하고, 합의한 설계를 ADR로 남긴 뒤 구현한다. 이유: 개발자가 설계에 능숙해지는 것이 목표 (오늘 같은 수준의 설계를 혼자 할 수 있게).
8. **이 문서는 살아있는 문서다.** 계획이나 작업 방식이 바뀌면 Claude가 이 파일을 수정한다.
9. **인프라는 모두 기록한다.** 구성, 결정 이유, 트러블슈팅을 `docs/infra/`에 남긴다.

## 7. 코드 컨벤션

- 패키지 구조는 서비스마다 `api`(컨트롤러·DTO) / `application`(유스케이스) / `domain` / `infrastructure`(JPA·Kafka·외부 연동).
- `core`는 모듈 먼저, 그 안에 계층: `lab.bank.core.<account|ledger>.{api, application, domain, infrastructure}`. 다른 모듈은 모듈 루트 패키지의 공개 클래스만 쓴다.
- `lab.bank.core.shared` (2026-11-13 결정): 공유 커널. account·ledger·posting이 함께 쓰는 **돈 개념의 값 객체만** 둔다 (`Amount`, `IdempotencyKey`). 유틸·공통 DTO·헬퍼는 넣지 않는다. 다른 core 모듈을 의존하지 않는다.
- 값 객체 (2026-11-13 결정): 값 하나로 판단되는 입력 규칙(금액 > 0, 멱등 키 길이)은 Kotlin `@JvmInline value class`가 생성 시 검증한다 (parse, don't validate). 메서드 시그니처까지만 쓰고 **JPA 엔티티 필드와 API DTO는 원시 타입**으로 둔다 (엔티티: name mangling, DTO: 외부 계약과 내부 모델 분리). 필드 여러 개를 함께 보는 규칙(`from == to`)은 유스케이스 입구에서 검사한다.
- 서비스별 아키텍처 (2026-11-12 결정): `core`는 **JPA 엔티티가 곧 도메인** (락·트랜잭션 실험이 `@Version`·더티 체킹·비관적 락과 붙어 있어서). `transfer`는 **헥사고날** (core·`bank-mock`·DB·Kafka 등 외부 의존이 많고, 포트를 가짜로 바꿔 타임아웃·결과 불명을 테스트하기 위해). 면접 포인트: 서비스 성격에 따라 아키텍처를 다르게 골랐다.
- 파일 하나에 최상위 선언 하나 (2026-11-12 결정, 긴 파일은 읽기 힘들어서). enum도 각자 파일로. 예외: Request/Response DTO는 모듈별 `api/<Module>Dtos.kt` 한 파일에 모은다. sealed 예외 계층(`<Module>Exception.kt`)도 한 파일에 둔다 (하위 타입 목록이 한눈에 보여야 해서).
- 금액은 `Long`(원 단위) 또는 `BigDecimal`. `Double`은 절대 쓰지 않는다.
- 상태 변경은 도메인 메서드로만 한다. 엔티티의 setter를 밖에서 호출하지 않는다.
- 모든 Kafka 컨슈머는 멱등해야 한다. 이벤트에는 고유 `eventId`를 넣는다.
- 외부 호출에는 반드시 타임아웃을 건다.
- 테스트는 Testcontainers(PostgreSQL, Kafka, Redis)로 실제 의존성에 붙여서 돌린다.
- 로그에는 traceId가 찍히도록 한다. 계좌번호 같은 민감 정보는 마스킹한다.

## 8. 실험 기록 템플릿

주차마다 `docs/experiments/week-XX-<주제>.md`로 남긴다. 이 템플릿이 그대로 블로그 글과 면접 답변의 뼈대가 된다.

```markdown
# Week XX. <주제>

## 문제
무엇이 왜 문제인가 (1~2문장)

## 재현
- 재현 방법 (테스트 / k6 시나리오 / 장애 주입 방법)
- 재현 결과 (로그, 잔액 불일치, 트레이스, 대시보드 캡처)

## 선택지 비교
| 방법 | 장점 | 단점 |
| --- | --- | --- |

## 적용한 해결책과 이유

## 결과 (전후 수치)
| 지표 | 전 | 후 |
| --- | --- | --- |

## 배운 점 / 한계
```

## 9. 컨트롤 패널과 시각화 가이드

이 프로젝트에서 사람들이 가장 먼저, 가장 오래 보는 것이 컨트롤 패널이다. **2분 시연만으로 "이 사람은 장애를 이해하고 다룰 줄 안다"가 전달되는 화면**을 목표로 한다.

### 화면 구성
- **왼쪽, 실험 조작:** 시나리오 버튼(동시 출금 폭격, 컨슈머 죽이기, 외부 은행 지연, 핫 파티션 등)과 실험 스위치(락 전략, 멱등 처리, 서킷 브레이커, 게이트웨이 모드)
- **가운데, 서비스 토폴로지:** 서비스와 Kafka·DB를 노드로 그리고, 상태에 따라 색이 바뀐다 (정상 / 지연 / 장애). 죽은 파드는 즉시 눈에 띄어야 한다
- **오른쪽, 실시간 지표:** TPS, p99, 에러율, 컨슈머 랙, 파드 상태, **잔액 정합성**(전체 잔액 합 = 기대값 여부)
- **하단, 타임라인:** "14:02:10 컨슈머 파드 종료 → 14:02:40 랙 12,000 → 14:03:30 회복" 같은 실험 이벤트 로그. 차트에도 같은 시점에 세로선 마커를 표시한다

### 디자인 원칙
- **다크 테마 기본.** 관제 화면 느낌, 시연 영상에서도 잘 보인다
- **색은 의미로만 쓴다.** 초록=정상, 노랑=저하, 빨강=장애, 그 외 강조색은 하나. 장식용 색 금지
- **한눈에 보이는 숫자가 먼저.** 큰 숫자 카드(TPS, p99, 에러율, 랙, 정합성) → 그 아래 추이 차트
- **전후 비교가 핵심 장면이다.** 스위치를 바꾼 시점을 차트에 마커로 남겨 "락 없음 → 잔액 깨짐 → 낙관적 락 → 정상"이 한 화면에서 읽히게 한다
- **애니메이션은 상태 변화에만.** 노드 색 전환, 숫자 카운트업 정도. 과한 효과 금지
- 데모 화면(계좌 조회·송금)은 최소한으로 둔다. 시간은 컨트롤 패널에 쓴다

### 작업 방식
- UI 작업은 **목업 → 개발자 확인 → 구현** 순서로 한다. 큰 화면 변경 전에는 레이아웃을 먼저 제안한다
- Grafana로 충분한 지표는 iframe 임베드로 끝내고, 잔액 정합성·토폴로지·타임라인처럼 이 프로젝트만의 시각화는 직접 그린다
- Grafana 대시보드는 `deploy/grafana/`에 JSON으로 커밋해서 재현 가능하게 유지한다
- 시나리오를 추가할 때는 한 세트로 만든다: `load-test/` k6 스크립트 + `chaos-control` 시나리오 정의 + 패널 버튼 + 실험 기록

## 10. 자주 쓰는 명령

실제 환경이 정해지면 채운다.

```bash
# 빌드 / 테스트
./gradlew build
./gradlew :services:core:test

# 로컬 실행
# TODO

# 배포 (Argo CD가 main 브랜치를 동기화)
# TODO

# 부하 테스트
k6 run load-test/<script>.js

# 프론트엔드
cd web && pnpm install && pnpm dev
```
