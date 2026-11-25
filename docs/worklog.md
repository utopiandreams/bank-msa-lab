# 작업 일지

세션마다 무엇을 했고, 무엇을 왜 정했고, 무엇이 터졌는지를 쌓는다. **이력서 개인 프로젝트란과 면접 답변의 원재료**다.

- 실험(재현 → 해결 → 수치)은 `docs/experiments/`, 큰 설계 결정은 `docs/adr/`, 인프라 상세는 `docs/infra/`에 따로 둔다. 여기에는 요약과 링크만.
- 사실만 적는다. 수치는 실제로 잰 것만.
- 맨 아래 **이력서 소재**는 항목이 생길 때마다 갱신한다.

---

## 2026-11-11 · 1주 차 — 온프레미스 k8s 클러스터와 GitOps

**한 일**
- Terraform(`bpg/proxmox`) + cloud-init으로 Proxmox VM 4대(컨트롤 플레인 1 + 워커 3)와 k3s 클러스터 구성. 빈 Proxmox에서 `terraform apply` 한 번, 약 2분
- Argo CD(App of Apps), MetalLB(L2), Traefik Ingress. Git push → 자동 반영, `kubectl delete` → 몇 초 안에 self-heal 확인
- Wake-on-LAN + 종료 스크립트로 실험할 때만 켜고 끄기 (Raycast 명령)

**결정**
- k3s: 메모리 32GB 제약, 본게임은 MSA. 최소 권한 Proxmox 토큰. 상세: `docs/infra/README.md`

**트러블슈팅**
- MetalLB 0.16이 BGP용 FRR-K8s를 기본으로 띄움 → L2만 쓰므로 비활성화, 노드당 파드 2개 → 1개
- `X-Forwarded-For`에 고객 IP 대신 `10.42.x.1` → SNAT 때문. `externalTrafficPolicy: Local` 전후 실측 (`docs/infra/notes/k8s-service-networking.md`)

---

## 2026-11-11 · 2주 차 — 서비스 경계 (ADR-0001)

**결정**
- **계정계 코어 모놀리스(account + ledger, DB 하나) + 주변 MSA.** 분산 경계를 "쪼갤 수 있는 곳"이 아니라 "실제로 갈라지는 곳"(채널 ↔ 코어, 코어 ↔ 외부 결제망)에 둔다. 사가는 롤백할 수 없는 외부가 끼는 타행 이체에서. 상세: `docs/adr/0001-transfer-usecase-and-service-boundaries.md`
- Java 25 / Spring Boot 4.1 / Spring Cloud 2025.1. Java 25는 14주 차에 가상 스레드 pinning 해결(JEP 491)을 21과 비교하려고. 21 vs 25를 다시 검토했지만 25 유지 (25도 출시 14개월 된 LTS)

---

## 2026-11-12 · 2주 차 — 멀티모듈과 배포 파이프라인

**한 일**
- Gradle 멀티모듈 + build-logic 컨벤션 플러그인(Java 25 toolchain, JUnit 6.0.3) + 버전 카탈로그
- **push 한 번으로 클러스터 배포:** GitHub Actions(테스트 → amd64 이미지 → GHCR → values 태그 커밋) → Argo CD → 롤링 업데이트. 이미지 pull부터 Ready까지 약 14초
- 모든 Spring 서비스가 쓰는 **공통 Dockerfile 하나 + 공통 Helm 차트 하나**. 코드만 바뀐 재배포는 앱 레이어(12KB)만 새로 받음 (의존성 레이어 27.6MB와 분리)

**결정** (상세: `docs/infra/README.md` 구성 요소 표)
- 레지스트리 GHCR, 태그 갱신은 CI가 values 커밋(배포 = 커밋 → 감사 추적, `git revert`로 롤백), `latest` 금지
- 공통 차트에 실무 기본값 내장: 프로브 3종, CPU limit 없이 메모리 limit만(+ `MaxRAMPercentage=75`), `maxUnavailable: 0` 무중단 롤링, preStop → graceful shutdown 종료 순서, 비root·읽기 전용 루트 파일시스템

**트러블슈팅**
- `include("core")` → Gradle 9는 없는 디렉터리를 include하면 에러. 실제 경로 `services/core`에 맞춰 `include(":services:core")`
- build-logic의 `setting.gradle.kts` 오타(s 누락) → Gradle이 파일을 무시, 저장소·카탈로그 설정이 적용 안 됨
- precompiled script plugin을 `src/main/`에 둠 → `kotlin-dsl`은 `src/main/kotlin/`만 봐서 플러그인 ID가 안 생김 ("plugin not found")
- `Catalog named libs doesn't exist` → build-logic settings의 카탈로그는 build-logic 자기 스크립트용. 플러그인 코드는 **적용되는 프로젝트**에서 실행되므로 메인 빌드의 카탈로그를 찾는다. 원인은 `gradle/libs.versions.toml`이 0바이트
- 맥북은 arm64, 노드는 amd64 → 로컬 빌드 이미지는 노드에서 `exec format error`. 정식 이미지는 CI에서 `linux/amd64`로 빌드

---

## 2026-11-12 · 3-1주 차 — PostgreSQL과 account 모듈

**한 일**
- CloudNativePG 오퍼레이터 + core 전용 PostgreSQL 18.6 (DB per service). 계정·비밀번호는 오퍼레이터가 Secret으로 생성 → 환경변수로 주입 (values에 비밀번호 없음)
- account 모듈: 계좌 개설·조회 API, 입출금 도메인 규칙, 업무 예외(sealed class + 에러 코드 + RFC 9457 Problem Details), Flyway 마이그레이션(모듈별 스키마 `account`)
- 테스트 13개: 도메인 단위(경계값: 잔액 전부 출금 성공, 1원 초과 거절, 거절 시 잔액 불변) + Testcontainers 리포지토리(더티 체킹으로 version 0 → 1)
- 클러스터에서 Flyway V1 적용, 실제 DB로 계좌 개설·조회 확인

**결정**
- **core는 JPA 엔티티가 곧 도메인, transfer는 헥사고날.** core의 핵심(락·트랜잭션, 7주 차)은 `@Version`·더티 체킹·비관적 락과 붙어 있어 매핑 계층이 방해. transfer는 core·`bank-mock`·DB·Kafka 등 외부 의존이 많아 포트를 가짜로 바꿔 타임아웃·결과 불명을 테스트할 가치가 큼
- core 패키지는 모듈 먼저(`account`, `ledger`), 그 안에 계층. 다른 모듈은 루트 패키지의 공개 클래스만
- 인증은 gateway, 인가(내 계좌인가)는 도메인을 아는 서비스 → Account에 예금주 ID
- 입출금 API는 원장과 함께 연다 (원장 없이 잔액만 바뀌는 경로를 처음부터 만들지 않기)
- 3주 차 범위가 커서 2주로 늘림 (3-1 core, 3-2 transfer·gateway), 총 20주

**버그 / 트러블슈팅**
- **음수 금액 검증 누락:** 첫 구현에서 `deposit(-100만)`은 잔고 검사 없는 출금, `withdraw(-100만)`은 돈 생성. 리뷰에서 발견 → 0원 이하 거절 + 테스트로 고정
- 음수 입금 테스트가 `assertFailsWith` 밖에서 한 번 더 호출해 실패 → 코드가 아니라 테스트 버그
- `application.yaml` 끝에 `spring:` 중복 → `DuplicateKeyException`으로 전체 테스트 실패
- 로컬 셸의 `java`가 21 → Java 25로 컴파일한 jar 실행 시 `UnsupportedClassVersionError` (class file 69 vs 65)
- 앱이 UUID를 만들면 Spring Data가 기존 엔티티로 보고 SELECT 후 merge → `@Version`이 null이면 새 엔티티로 판단해 바로 INSERT

---

## 2026-11-13 · 3-1주 차 — ledger 원장과 거래 처리(입금·출금·당행 송금)

**한 일**
- ledger 모듈: 거래(JournalEntry, 애그리거트 루트) 1 : 분개 줄 N, 차변 합 = 대변 합을 생성 시 검사, 멱등 키 UNIQUE (Flyway V2)
- posting 모듈: 원장 기록과 잔액 변경을 한 트랜잭션으로 지휘. account와 ledger는 서로를 모른다
- 같은 멱등 키 재요청 → 같은 거래 ID + `replayed`, 같은 키에 다른 내용 → `IdempotencyConflictException`
- 통합 테스트(Testcontainers, 테스트에 `@Transactional` 없음): 롤백(원장 INSERT 뒤 실패해도 함께 롤백), 멱등, 송금 합계 불변, **같은 키 동시 요청**
- 금액·멱등 키를 값 객체(`Amount`, `IdempotencyKey`)로 → 검증이 "입구 if문"에서 "타입"으로 이동. core 테스트 36개

**결정**
- 트랜잭션 경계는 `TransactionTemplate`: 실패를 트랜잭션 **밖에서** 잡고 새 트랜잭션으로 재시도해야 하는데, `@Transactional`은 같은 클래스 안 호출(self-invocation)에서 안 걸린다
- 동시 중복 요청: 둘 다 "거래 없음"을 보고 INSERT → 늦은 쪽 UNIQUE 위반 → 트랜잭션 밖에서 잡아 **한 번** 재시도 (재시도 땐 먼저 커밋된 거래를 보고 replayed)
- 멱등 판정은 해시를 저장하지 않고 "거래 종류 + 분개 줄 구성(계정·계좌·방향·금액)" 비교 → 받는 계좌만 바꾼 요청도 충돌로 잡힌다
- 송금 1건 = 거래 1건(분개 2줄). 출금·입금 거래 2건으로 쪼개지 않는다 (대사에서 연결이 사라짐)
- **400 vs 500 기준: "클라이언트가 요청을 고쳐서 해결되는가".** 값 하나 규칙은 값 객체, 필드 여러 개 규칙(`from == to`)은 유스케이스 입구, 도메인의 `require`는 마지막 방어선(여기 걸리면 우리 버그 → 500)
- 공유 커널 `lab.bank.core.shared`: 돈 개념 값 객체만. 엔티티 필드·API DTO는 원시 타입 (value class name mangling / 외부 계약과 내부 모델 분리)
- 진행 호흡을 길게: 주차 속도보다 배운 것을 정리하고 복기하는 것을 우선 (`docs/learning/`)

**버그 / 트러블슈팅**
- **송금에서 받는 쪽도 출금:** `transfer`가 받는 계좌에 `withdraw` 호출. 컴파일·기존 테스트 모두 통과 → 리뷰에서 발견, "합계 불변" 테스트로 고정. 원장은 맞고 잔액만 틀려서 대사 전엔 안 드러나는 종류
- **0원 입금이 500:** ledger가 account보다 먼저 실행돼 업무 예외가 아니라 원장의 `require`(버그 취급)에 걸림 → 입구 검증 → 값 객체로 해결
- **65자 멱등 키가 500:** DB `varchar(64)` 위반이 `DataIntegrityViolationException`이라 중복 키 재시도에 잘못 잡힘 → `IdempotencyKey`에서 길이 검사
- `@MockitoSpyBean` 리포지토리에서 `callRealMethod()` 실패: Spring Data 리포지토리는 인터페이스의 JDK 프록시라 부를 구현이 없다 → 스파이의 기본 응답(원래 빈에 위임)을 꺼내 사용
- 동시성 테스트를 "출발 신호" 방식으로 하면 경합이 운에 달림 → 조회 직후 `CountDownLatch(2)`로 두 스레드를 맞물리게 해 **매번** 재현, `verify(times(3))`로 경합이 실제로 일어났음을 증명

---

## 2026-11-14 · 3-2주 차 — transfer → core 송금, 결과 불명, 고객 멱등 키

**한 일**
- 송금 도메인(개발자): 생성 팩토리, 상태 전이 규칙(`canTransitTo`), 의도별 전이 메서드, `require`(인자) / `check`(상태) 구분
- core 호출 어댑터: RestClient + connect/read 타임아웃(설정값), core 응답 → 완료·거절·결과 불명 분류, 어댑터 테스트 14개 (응답 코드별 + 진짜 소켓으로 read 타임아웃·연결 거부)
- 송금 use case(개발자): 처리중 커밋 → 트랜잭션 밖 core 호출 → 결과 커밋. 금액 값 객체(`Amount`, 헥사고날이라 도메인 필드로도), 업무 예외 → 400 ProblemDetail, 송금 API(POST 201 + Location, GET)
- 고객 멱등 키(T-2, 로직은 개발자): Flyway V2(추가 → 채우기 → NOT NULL → UNIQUE), 같은 키 재요청은 기존 송금의 현재 상태(200), UNKNOWN은 같은 송금 ID로 재전송, 내용 다르면 409, 동시 요청은 UNIQUE 위반 → 포트 예외 → 이긴 쪽을 읽어 재요청 처리
- 클러스터 확인: transfer → core 송금·거절(잔액 부족, 계좌 없음)·400, transfer 송금 ID = core 멱등 키, transfer `coreEntryId` = core 원장 ID, 같은 키 201 → 200·잔액 한 번만·409·400
- 테스트: transfer 56개 (멱등 명세는 먼저 빨간불로 써 두고 개발자 구현으로 초록)

**결정**
- **응답 분류 기준: "core가 처리하지 않았음이 확실할 때만 거절".** 409(멱등 충돌)·5xx·2xx인데 body 불가·타임아웃은 결과 불명. UNKNOWN의 정의는 원인이 아니라 "상태 조회로 확정해야 하는 송금"
- 포트는 예외 대신 sealed 결과(`Completed`/`Rejected`/`Unknown`) → `when`으로 처리 누락을 컴파일러가 잡음. 포트 인자는 `Transfer` 통째로 (보내는/받는 계좌 UUID 순서 실수 방지)
- REQUESTED는 저장하지 않음 (동기 처리라 PROCESSING과 구별되는 정보가 없음). PROCESSING의 의미는 "호출했을 수도 있다"
- POST 응답은 결과와 무관하게 201(송금 자원은 생김), 결과는 body `status`. 재요청은 200
- HTTP 클라이언트는 RestClient `exchange` (블로킹끼리는 차이가 작고, 응답 코드별 분류가 한곳에 보여서). Boot 4 자동 설정 모듈 없이 직접 구성
- 고객 멱등 키를 8주 차에서 당김: 메커니즘은 core에서 증명했고, transfer에서만 새로 생기는 문제(처리 중이 DB에 보임, UNKNOWN 뒤 재시도)에 집중
- 멱등 방식: 응답 저장(Stripe)이 아니라 **송금 행 자체가 기록** → 키와 송금이 같은 INSERT라 원자적, 해소된 결과가 최신으로 보임. 그래서 gateway가 아니라 transfer에서 처리. 키 범위는 일단 전역(JWT 생기면 사용자별)
- UNKNOWN 재전송이 또 모름이면 UNKNOWN → UNKNOWN 전이 허용 (개발자 선택: `updatedAt` = 마지막 시도 시각, 해소 스케줄러 백오프 기준)

**버그 / 트러블슈팅**
- **transfer가 21시간째 CrashLoopBackOff (재시작 133회):** Flyway `Found non-empty schema(s) "transfer" but no schema history table`. `search_path`가 `"$user", public`이라 V1이 계정 이름과 같은 `transfer` 스키마를 만든 뒤로 Flyway의 현재 스키마가 public → transfer로 바뀌어 기록 테이블(public)을 못 찾음. 첫 기동만 성공하고 재시작마다 실패. `spring.flyway.default-schema: public`으로 고정. Testcontainers 계정이 `test`라 조건이 안 생겨 못 잡았음 → 계정을 클러스터와 같은 `transfer`로 맞추고 `flyway.migrate()` 재실행으로 재현 → 수정 → 클러스터에서 파드 삭제로 재기동 확인
- 도메인에 `require(amount > 0)`이 생기자 "같은 계좌 DB 제약" 테스트가 도메인에서 먼저 막혀 실패 → DB 제약 테스트는 SQL 직접 INSERT로
- 가짜 core가 저장한 `Transfer` 참조가 호출 뒤 use case에 의해 COMPLETED로 바뀌어 "호출 시점 상태" 검사 실패 → 가변 엔티티는 호출 순간에 값을 기록
- 타임아웃 테스트가 2초 걸림: 가짜 서버의 `sleep`을 `stop()`이 기다림 → 래치로 붙잡고 끝나면 풀기 (0.2초)
- `assertFalse(e is DuplicateIdempotencyKeyException)`: 상속 관계가 없는 타입이라 항상 false인 의미 없는 단언 (컴파일러 경고로 발견)
- 개발자 구현에서: `validate()` 조건 반대(`!=`), `IllegalArgumentException`으로 400 의도(→ 500), IDE에서 다른 테스트 클래스를 돌려 "다 통과"로 오인
- push 거절: CI의 이미지 태그 커밋이 먼저 올라감 → `pull --rebase` 후 push

**다음**
- `docs/learning/2026-11-13.md`, `2026-11-14.md` 개발자 요약 → Claude 리뷰
- 3-2주 남은 것: gateway(라우팅 + JWT 인증). 3주 차가 다른 주의 두 배 이상이라 일정 선택 필요: ① 3-3으로 며칠 더 ② 4주 차(Kafka) 앞에 붙이기. 8주 차가 멱등이 빠져 가벼워졌으니 전체 분량은 그대로

---

## 2026-11-14 (오후) · 3-2주 차 — gateway 인증·인가 (3주 차 완료)

**한 일**
- ADR-0002 (gateway 인증과 외부 노출 범위): 학습용 발급 + HS256 + `X-User-Id` 덮어쓰기 + 외부 진입점 하나
- G-1 gateway 모듈·라우팅·배포 → G-2 JWT(Security Resource Server, 테스트 15개 중 공격 시나리오 7개) → G-3 Ingress 닫기
- 멱등 키 사용자별(transfer V3), G-4 계좌 소유자 인가(core)
- 매번 **클러스터에서 문제를 먼저 재현**: 위조 헤더 통과, 전역 멱등 키로 남의 송금이 보임, 영희 토큰으로 철수 계좌 3만 원 출금 → 각각 고친 뒤 같은 시나리오로 확인
- 실무 함정 노트 `docs/learning/pitfalls.md` 시작 (18개)

**결정**
- gateway를 3주 차 안에서 끝냄 (4주 차로 미루지 않음)
- 발급은 gateway 학습용(비밀번호 없음), 리프레시·Keycloak은 백로그 "인증 서버 분리"
- 소유자 검사는 core가 출금 트랜잭션 안에서, **원장 멱등 판정 뒤** (재요청은 다시 판정하지 않음)
- 남의 계좌는 404 `ACCOUNT_NOT_FOUND` (존재 여부 누출 방지), 구분은 로그에만
- 백로그 추가: 노드 확장, 인증 서버 분리, NetworkPolicy·제로 트러스트, CI 변경 감지

**버그·트러블슈팅**
- 계좌에 소유자가 없다고 잘못 판단 (셸 glob 오류로 검색 실패를 확인 안 함) → 클러스터 응답의 `ownerId`를 보고 정정
- Boot 4에서 resource server 스타터 이름 변경 (`spring-boot-starter-security-oauth2-resource-server`)
- WebFlux Kotlin DSL에 `authorize(method, pattern)` 없음 → `pathMatchers(...)`
- Kotlin 주석 안의 `/accounts/**`가 중첩 주석으로 열려 컴파일 실패
- GHCR 패키지는 public 레포에서 자동 public (문서의 "수동 전환" 안내가 틀렸음)

**다음**
- 4주 차: Strimzi Kafka + notification. 그 전에 노드 확장을 할지 결정 (Kafka 브로커 메모리)
- 주말: `docs/learning/` 복기 (11-13, 11-14, pitfalls 1·2·9·17), ADR-0002 면접 30초 답변

## 2026-11-17 ~ 18 · 인프라 확장 준비 + 4주 차 Kafka·notification (4주 차 완료)

**한 일**
- 두 번째 Proxmox 호스트 `proxmox2` (i5-14600K, DDR5 32GB, NVMe 1TB, PVE 9.2): 개발자가 USB로 설치, Claude가 무료 저장소·업그레이드·WOL·최소 권한 Terraform 토큰
- 컨트롤 플레인 중재자 노트북 `bank-cp3` (AMD A6, Ubuntu Server): 키 전용 SSH, 덮개·절전 설정, **etcd 디스크 적합성 측정** (fdatasync p99 16.6ms, 권장 10ms 미달이지만 중재자로 사용)
- 4주 차: ADR-0003 → Strimzi 1.3.1 + Kafka 4.3.2(KRaft 1대) → transfer 발행 → notification(전용 DB, 멱등) → 클러스터에서 송금 → 알림 SENT 확인
- 공부 자료: Raft·합의(`consensus.md`), 관측성(`observability.md`: CPU 판독표, 그래프 모양 도감, 트러블슈팅 10개, 재현 계획), pitfalls 19·20

**결정**
- 노드 확장은 9주 차(관측성) 전에. 워커 메모리 실측 13~21%라 Kafka는 기존 클러스터로 충분 ("Kafka 전에 확장"은 실측 없이 한 판단이라 정정)
- 두 Proxmox 호스트는 클러스터로 묶지 않음 (2대는 quorum 불가). 컨트롤 플레인 HA는 세 번째 장애 영역(노트북)으로
- 이벤트: 발행은 transfer, 끝 상태로 **바뀌는 순간**만, 키 = 송금 ID(CLAUDE.md 기본값 "계좌 ID"의 예외), 스키마는 `libs/common` 공유(타협), 커밋 뒤 바로 발행 = 6주 차 유실 재현용 구멍
- 로드맵은 끝까지 완주 (개발자: "현업에 몰두한다고 공부가 쌓이지 않더라")
- 백로그: 외부 공개(Cloudflare Tunnel, 13주 차 전), Tailscale, 관측성 감각 트랙, 플랫폼 엔지니어링 트랙

**버그·트러블슈팅**
- proxmox2 `apt update` 실패: 공유기 DNS가 고정 IP 호스트 질의에 응답 안 함 → 공개 DNS 우선
- Terraform 토큰 스크립트가 tfvars를 통째로 덮어쓰는 구조 + PVE 9에서 `VM.Monitor` 권한 삭제 → 변수 한 줄만 교체, 버전 분기
- runbook의 게이트웨이를 `.1`로 잘못 적음 (실제 `.254`) → 설치 전에 발견
- PVE 9 설치 화면에서 랜선을 늦게 꽂아 인터페이스가 안 보임 → 재부팅. 인터페이스 이름 고정(`nic0`) 사용
- notification Flyway V1에 `version` 컬럼 누락 → `ddl-auto: validate`가 기동 시 잡음
- Kafka 프로듀서 `max.block.ms` 기본 60초면 Kafka 장애 때 송금 API가 건마다 60초 멈춤 → 1초로, 테스트로 고정 (pitfalls 20)

**다음**
- **순서 (2026-11-18 합의):** ① 관측성 기본 스택(Prometheus·Grafana, 앱 지표 포함) → ② k6 기준선을 그래프 보면서 → ③ 랜선 오면(저녁) 노드 확장을 그래프 보면서
- 노드 확장: proxmox2 워커 VM, k3s SQLite → 내장 etcd + 서버 3대(`bank-cp` / `bank-cp2` / `bank-cp3`), 호스트 하나 끄고 실측
- k6 첫 기준선: `load-test/transfer-baseline.js` (gateway → transfer → core, constant-arrival-rate로 초당 10 → 30 → 60), p50/p95/p99·에러율·하키 스틱 지점을 `docs/experiments/`에 기준선으로
- 5주 차 전: design-drills 04 타행 이체 (ADR-0004 초안)
- 주말 복기: Kafka Part 1, Raft, 관측성 Part 7·8, pitfalls

---

## 2026-11-18 (오후) · 관측성 기본 스택 준비 (10주 차에서 앞당김, 클러스터 꺼져 있어 미적용)

**한 일**
- `kube-prometheus-stack` 93.4.0을 Argo CD 앱으로 추가 (Prometheus 15초 수집·7일/8GB 보존·PVC 10Gi, Grafana Ingress, node-exporter, kube-state-metrics). k3s 내장 컴포넌트 수집 끔, Alertmanager는 11주 차
- 대시보드를 코드로: `deploy/grafana/` (kustomize로 JSON → ConfigMap). Strimzi Kafka·KRaft·Kafka Exporter, CloudNativePG, JVM (Micrometer)
- Kafka: `strimziMetricsReporter` + Kafka Exporter(컨슈머 랙). Kafka·CNPG PodMonitor (`deploy/platform/monitoring/`)
- 서비스 4개: `micrometer-registry-prometheus`, actuator를 관리 포트 8081로 분리, 프로브는 앱 포트 `/livez`·`/readyz`, API 지연 히스토그램, `application` 라벨. 공통 차트가 PodMonitor 생성
- 테스트 148개 통과 (core 56, transfer 68, notification 6, gateway 18). gateway `ManagementPortTest` 추가

**결정**
- actuator 관리 포트 분리: gateway Ingress로 지표가 인터넷에 열리는 것을 막는다. Service에 넣지 않고 PodMonitor로 파드를 직접 수집
- Grafana 관리자 비밀번호는 미리 만든 Secret (차트 무작위 생성은 Argo CD에서 동기화마다 바뀜)
- etcd 지표는 내장 etcd 전환(노드 확장) 뒤에

**버그·트러블슈팅**
- 관리 포트를 나눴는데 Prometheus가 401: WebFlux Spring Security 체인이 관리 포트에도 걸린다 → `/actuator/**` 허용 + "앱 포트에는 actuator 404" 테스트로 보호 (pitfalls 21)

**다음**
- 노드 올리면: Grafana Secret 생성 → push → Targets 전부 UP 확인 → 메모리 실측으로 예산 갱신 (`docs/infra/README.md` "관측성 기본 스택" 확인 절차)
- 그다음 k6 기준선 → 노드 확장 (순서 그대로)

## 2026-11-18 (저녁) · 관측성 스택 적용 + k6 첫 기준선

**한 일**
- 관측성 스택 클러스터 적용: 수집 대상 17묶음 전부 UP, 대시보드 29개, 지표 외부 차단 확인(밖에서 404). 워커 메모리 +1.4GB(예산과 일치)
- node-exporter를 컨트롤 플레인에도(toleration), instance 라벨을 노드 이름으로(relabeling)
- Raycast: Argo CD·Grafana 바로 열기, 상태에 proxmox2·중재자 노트북(전원·배터리)
- k6 기준선: `load-test/seed-accounts.sh`(계좌 200개, 입금은 port-forward 관리자 통로) + `transfer-baseline.js`(5→10→30→60건/초, 단계별 태그, 잔액 합 검증). 결과 `docs/experiments/week-05-k6-baseline.md`

**결정**
- 계좌 준비는 시드 스크립트 (gateway에 입금 경로를 열지 않음, ADR-0002 유지). 회원·인증을 먼저 해도 입금 경로 문제는 안 풀림 → 순서 유지
- 계좌 200개 무작위(경합 없는 평소 모습), 맥북(Wi-Fi)에서 실행

**버그·트러블슈팅**
- 기준선 중 UNKNOWN 3건: core 낙관적 락 충돌(`@Version`)이 매핑 안 돼 500 → transfer가 결과 불명으로 분류. 돈은 안전(롤백, 잔액 합 불변). 처리 방향은 7주 차 설계로 (pitfalls 22)

**다음**
- 부하를 수백 건/초로 올려 하키 스틱과 병목 찾기 (그 전에 Tomcat 스레드 지표 켜기)
- 노드 확장 (proxmox2 전원·랜선 오면)

---

## 2026-11-18 (밤) ~ 19 · k6 stress와 장애 분석, 노드 확장, 라이트사이징

**한 일**
- k6 stress(20 → 100 → 200 → 400 → 800건/초): 200~400건/초에서 꺾임. 800건/초에서 transfer·gateway OOMKilled, HTTP 실패 46.8%, UNKNOWN 2,209건. 잔액 합 2억 불변 확인
- **개발자가 Grafana에서 직접 원인 추적** (Claude는 한 단계씩 화면 위치만 안내): p99 급등(transfer 3.5초) → 파드 메모리가 한도까지 → 재시작 4회·OOMKilled → 힙은 여유, Tomcat 스레드 200·WAITING 약 300 → Hikari 활성 10/10, 대기 183
- Raycast·스크립트: 켜기/끄기를 호스트별(전부/proxmox/proxmox2) 선택 + 호스트끼리 병렬
- 노드 확장: proxmox2에 워커 bank-w4·w5(10 vCPU / 12GB). Terraform을 호스트별 모듈로, 모든 노드에 `topology.kubernetes.io/zone`
- 라이트사이징: 서비스 4개·DB 3개 메모리 request = limit = 512Mi (CPU request 100m 유지, 실측 근거)

**결정**
- 커넥션 풀·스레드 문제는 고치지 않고 15주 차 "전" 기록으로 남김
- 홈(RED) 대시보드는 나중에 (오늘 찾은 쿼리가 재료)
- proxmox2 VM은 큰 워커 2대 (메모리가 한계라 잘게 나누면 고정 비용·조각화만 늘어남), HA는 별도 세션
- 메모리 request = limit: 메모리는 빌려 쓸 수 없어 request < limit이면 노드가 빡빡할 때 먼저 쫓겨남. "파드 하나는 200건/초까지", 그 이상은 거절(15주 차)·스케일 아웃(16주 차). CPU는 limit 없이 평소 부하 기준
- 메모리 증설(DDR4 ECC)은 보류: 실측 노드 사용률 7~44%. 꾸준히 70% 이상이거나 Pending이 생기면 다시 판단
- Terraform 복습은 선택 백로그 (핵심 아님, 개발자 결정)

**버그·트러블슈팅**
- proxmox가 네트워크에서 사라짐: 노트북 랜선을 만지던 22:19에 proxmox 자기 링크가 끊기고 10Mbps/Half로 붙었다 다시 끊김 → 호스트는 살아 있었는데 네트워크만 끊긴 상태라 Wake-on-LAN이 소용없었음. 전원 버튼(정상 종료) → 재부팅 후 1Gbps 정상
- Terraform 리팩터링으로 기존 VM이 재생성될 뻔한 경로를 `moved` + 렌더 결과 비교로 막음 (pitfalls 25)
- DB 메모리 변경 후 core-db가 약 4분 Terminating: Postgres 스마트 종료가 앱 커넥션 풀이 끊기기를 기다림 → `smartShutdownTimeout` 180초 후 강제 종료 (pitfalls 26)
- stress 메모리 피크 506Mi는 기준으로 못 씀: 한도에 막혀 멈춘 값 + 15초 수집 간격 사이에 넘은 순간은 안 찍힘

**다음**
- 5주 차: 타행 이체 사가 (설계 먼저: design-drills 04 → ADR-0004)
- 노드 확장 후 k6 재측정 (파드 배치 노드 기록), 컨트롤 플레인 HA (별도 세션)
- UNKNOWN 2,209건: 같은 키 재시도로 정리할지, 8주 차 해소 스케줄러 테스트 데이터로 둘지

---

## 2026-11-19 (새벽) · 5주 차 시작: 사가 설계, bank-mock, core 타행 출금·취소

**한 일**
- 설계 연습 04 (타행 이체): 개발자가 흐름·상태 머신 초안 → 장애 질문 리뷰 → ADR-0004 초안
- S-1 `bank-mock`: 성공/거절 판정(수취 계좌 999* 거절 + 거절률 스위치), 같은 키 멱등, 테스트 10개, 배포·클러스터 확인
- S-2 core: 미정산 계정 `INTERBANK_UNSETTLED`, 타행 출금(소유자 검사), 출금 취소(역분개), Flyway V3, 테스트 11개 추가 (core 전체 67개 통과)

**결정 (개발자가 찾은 것)**
- **피벗 트랜잭션** = 외부 송금 성공. 앞(출금)은 보상, 뒤는 재시도만 (외부 보상은 불가: 이미 남의 은행 고객에게 감)
- WITHDRAWN(안 보낸 게 확실) / INTERBANK_REQUESTED(보냈을 수도) 분리 → 보냈을 수도 있으면 보상 금지, 확인 먼저 (이중 지급 방지)
- FAILED(돈이 안 움직임) ≠ CANCELLED(빠졌다 돌아옴): 원장·고객 내역·대사가 다르다
- 보상 키: 출금 키 재사용 불가(core가 출금 결과를 돌려줌) → 결정적 키 → 최종적으로 **원래 출금을 가리키는 자연 키**(`reverses_entry_id` UNIQUE). 금액을 요청에서 받지 않아 과다 환불이 구조적으로 불가
- 고객 응답은 이번엔 동기, 비동기·혼합은 8주 차에 보강 (포기가 아니라 순서). 미정산 계정은 지금, API는 같은 `POST /transfers` + `toBankCode`
- bank-mock 거절은 200 + REJECTED: 확실한 거절을 5xx로 주면 transfer가 결과 불명으로 보고 보상 대신 멈춘다

**다음**
- S-3 transfer 타행 사가 (Flyway: 송금 종류·은행 코드·새 상태, bank-mock 어댑터, 보상, CANCELLED 이벤트)
- S-4 클러스터 확인 (성공 / 거절 → 보상 → 잔액·미정산 합 0), 코레오그래피 비교 그림

---

## 2026-11-20 (새벽) · S-3 타행 사가 구현, proxmox2 WOL 해결

**한 일**
- proxmox2 WOL 실패 원인: ASUS BIOS가 키보드 없으면 F1 대기에서 멈춤 + `Power On By PCI-E` 기본값 꺼짐 → BIOS 설정 후 끄기/켜기 확인. runbook 체크리스트에 추가
- S-3 transfer 타행 사가: 상태 머신·사가 지휘는 개발자, 저장 어댑터(`@SecondaryTable`로 공통·상세 두 테이블)·API·발행 배관·리팩터링(당행/타행 유스케이스 분리)·테스트는 Claude. Flyway V5, 테스트 119개, 배포·V5 적용 확인
- 테스트가 잡은 버그: status `varchar(20)`에 `EXTERNAL_BANK_REQUESTED`(23자) 안 들어감 / 저장 반환값을 안 받아 다음 저장에서 낙관적 락 예외 / 첫 재요청 분기에 내용 비교 누락

**결정 (개발자가 찾은 것)**
- **"요청함" 상태는 원래 결과 불명이다** (API 호출과 트랜잭션을 못 묶으니까): 원격 호출마다 호출 전에 커밋하는 의도 상태 하나, 결과 불명은 따로 두지 않고 의도 상태에 남김 (13개 → 9개 상태, pitfalls 27)
- 상태를 나눌지는 "멈춘 송금을 복구 로직이 발견하면 무엇을 하나"가 다를 때만. 두 상태 사이에 원격 호출이 없으면 의심
- 재시도: 출금·보상은 같은 키로 재시도(멱등 보장), 외부 송금은 재전송 대신 결과 조회(8주 차)
- 이벤트는 끝 상태로 바뀌는 순간 한 번 (출금 알림은 core 계좌 이벤트의 몫)
- `BankCode`는 enum이 아닌 값 객체 (기관 코드는 합병·신설로 바뀌는 기준 정보, 폐지 코드의 과거 거래를 읽을 수 있어야 함)
- 당행·타행 같은 멱등 키는 원인이 적힌 409. 키를 종류별로 나누면 한 의도로 돈이 두 번 나갈 수 있어 지금 설계(통틀어 하나)가 맞음 (pitfalls 28)
- 오케스트레이션 = 영속화된 상태 머신. 워크플로 엔진(Temporal 등)은 도입하지 않고 비교 정리만

**다음**
- S-4 클러스터 확인 (gateway 경유: 성공 / 거절 → 보상 → 잔액·미정산 0, CANCELLED 알림), 코레오그래피 비교 그림, 5주 차 실험 기록
- 6주 차 Outbox: 설계 먼저 (design-drills 홉 분석표 + 장애 질문, CLAUDE.md 7-2)

---

## 2026-11-20 (밤) · S-4 클러스터 확인, 5주 차 마무리

**한 일**
- S-4 gateway 경유 확인: 타행 성공(201 COMPLETED, 잔액 −3만, 미정산 +3만) / 외부 거절(201 CANCELLED `ACCOUNT_NOT_FOUND`, 출금 + 취소 전표, 잔액·미정산 원복, CANCELLED 알림 SENT) / 같은 키 재요청(200, 새 전표·알림 없음)
- 오케스트레이션 vs 코레오그래피 비교: 코레오그래피 흐름은 개발자가 말로 설명, Claude가 그림·표로 정리
- 5주 차 실험 기록 `docs/experiments/week-05-saga.md`, 5주 차 완료 → 현재 6주 차

**결정 / 배운 것 (개발자가 찾은 것)**
- 코레오그래피는 서비스마다 작은 상태가 생기고 전체 상태는 어디에도 없다 → "내 송금 어떻게 됐어요?"에 답하려면 조회 모델(프로젝션)이 따로 필요
- "출금됨" 이벤트만으로는 당행·타행을 모른다 → 이벤트가 상대의 맥락(송금 ID)을 실어 날라야 함 = 다시 결합
- 커밋 후 발행 전 장애: 코레오그래피는 **기다리는 쪽이 없어** 송금이 조용히 멈춘다 → 모든 홉에 Outbox. 오케스트레이션은 호출자가 기다리므로 타임아웃 = 결과 불명 → 의도 상태에서 같은 키 재시도로 복구
- 미정산이 성공 건만큼 남는 건 정상 (은행 간 차액결제를 만들지 않음, 8주 차 대사 기준값)

**트러블슈팅**
- 확인 도중 gateway 토큰 15분 만료로 401 → 재발급 (TTL 설계대로)
- proxmox2(w4·w5) 꺼진 상태로 진행, transfer 파드는 w1로 재스케줄되어 있었음

**다음**
- 6주 차 Outbox: 설계 먼저 (design-drills 홉 분석표 + 장애 질문, CLAUDE.md 7-2). 재현 대상은 `TransferFinishedEvent` 커밋 뒤 바로 발행 구멍
- 11/20 공부 자료 요약 (개발자, 미룸)

---

## 2026-11-21 · 6주 차: Transactional Outbox, 중복 수신 클러스터 확인

**한 일**
- 유실 재현 테스트 `TransferEventLossTest` (프로듀서 연결 전 Kafka pause → COMPLETED인데 이벤트 없음, 대조군 정상)
- Outbox: Flyway V6, 모델·엔티티(Claude) / outbox 발행기·당행·타행 트랜잭션 안으로 이동(개발자) / 릴레이·전송 부품·정리 배치·테스트(Claude). 테스트 127개
- 클러스터: Kafka 브로커 파드 삭제(31초 중단) 중 송금 5건 → outbox에 쌓임, 릴레이 13회 실패 → 복구 3초 안에 5건 발행, 알림 5건 SENT
- 중복 수신: notification 0대 → 오프셋 10:06으로 되감기(5건) → 다시 1대 → 5건 모두 DUPLICATE, 알림 행 수 불변, 랙 0
- `OutboxSkipLockedTest`: A가 잠금 쥔 동안 B는 나머지만. SKIP LOCKED를 빼면 실패하는 것까지 확인
- 5주 차 S-4 (11/20 밤 기록 참고), 7주 차 곁다리로 MySQL 갭 락 실습 백로그

**결정 (개발자)**
- 폴링 릴레이(transfer 스케줄러), SKIP LOCKED, 발행 표시 + 일일 정리(정리는 멱등이라 락 없음)
- 포트는 그대로 두고 구현만 교체, 어댑터가 다른 포트를 거치지 않게 `OutboxEventPort` 삭제 (Claude가 리뷰에서 헷갈리게 짚었다가 정정)
- "커밋 전에 보내고 ack 받으면?" → ack 뒤 커밋 실패 = 유령 이벤트. dual write는 순서로 결과를 고른다 (pitfalls 30)

**버그·트러블슈팅**
- 첫 재현 실패: 연결 뒤 pause하면 "발행 실패"로 떠도 실제로는 기록됨 → 연결 전 pause로 바꿈 (pitfalls 29)
- 리뷰에서 잡은 것: 토픽 이름 하드코딩(`transfer-finished`), 메시지 키 빈 문자열(= 핫 파티션), JPQL식 네이티브 쿼리, `@Qualifier`가 테스트 가짜(@Primary)를 우회, 네이티브 쿼리 스키마 누락(search_path 의존), 타행 `toBankCode`에 계좌번호, CANCELLED 사유 누락
- 처음 통과한 재현 테스트가 릴레이 실패 경로를 안 지났음 (pause가 너무 짧음) → 3초 유지로 실패→재시도 경로까지
- 중복 재현 중 Argo selfHeal이 scale 0을 되돌림 → root부터 자동 동기화 끄고, 끝나고 root만 다시 켬 (pitfalls 31)
- zsh는 변수 안 명령을 단어로 안 나눔 → 함수로

**다음**
- 7주 차: 동시 출금으로 잔액 깨짐 재현 → 낙관적·비관적·Redis 분산 락 k6 비교. 설계 먼저 (CLAUDE.md 7-2)
- 11/20 공부 자료 요약 (개발자, 미룸)

---

## 2026-11-21 (밤) ~ 22 (새벽) · 7주 차: 락 전략 6개와 실험 스위치, Redis 분산 락

**한 일**
- 락 전략 스위치 `core.lock.strategy`: NONE(재현) / OPTIMISTIC(@Version + 트랜잭션 밖 재시도·지터, 재시도 지표) / PESSIMISTIC(FOR UPDATE, ID 순 잠금) / ATOMIC_UPDATE(조건부 UPDATE) / REDIS(pub/sub) / REDIS_SPIN. 실행 중 변경은 관리 포트 `POST :8081/actuator/experiments` (업무 포트에선 404)
- 테스트로 재현: NONE lost update(원장 8,000 / 잔액 9,000), 반대 순서 잠금 데드락. 핫 계좌(2,000원에 100원 × 40) PESSIMISTIC·ATOMIC·REDIS·REDIS_SPIN 정확히 20/20, OPTIMISTIC은 성공 20 / 충돌 실패 7 / 잔액 부족 13
- Redis: Redisson 4.8.0 승인, 클러스터에 `redis-0`(StatefulSet, 저장 없음, noeviction). 처음 명령 때 접속(lazy)이라 Redis 없이도 core가 뜸
- 락 범위 함정 재현 `RedisLockScopeTest`: 트랜잭션 안에서 unlock → NONE은 출금 2건 성공에 잔액 1건만, REDIS는 늦은 커밋이 버전 충돌. 감싸면 20건 전부 반영(락을 빼면 실패함을 확인)
- REDIS 계열 posting 경로 테스트: 핫 계좌·A↔B 송금·취소+동시 입금 모두 **버전 충돌 재시도 0** (잠금 범위가 맞다는 증거). core 테스트 94개
- 공부 자료 `docs/learning/concurrency.md` (판단 순서·전략별 대가·공정성·비교표), pitfalls 32·33(보강)·34

**결정 (개발자)**
- 전략은 지우지 않고 스위치로 비교, 관리 포트 API, k6는 클러스터 안 일회성 파드
- 계좌 잠금은 account가 감싸는 함수(`AccountLocks`)로 내주고 트랜잭션은 posting이 그 안에서 연다 (전략 판단은 account, 위치는 posting)
- 감싸는 순서 `재시도 { 중복 키 재시도 { 잠금 { 트랜잭션 } } }` (개발자 초안 그대로). 잠금 대기 상한 1초 → 503: transfer → core read-timeout 3초보다 짧게
- 분담: 잠금 래퍼 뼈대는 개발자, 포기 로직·나머지 메서드·테스트는 Claude (개발자: "사용법 익히기라 Claude가")

**배운 것 (대화)**
- 원자적 UPDATE: WHERE 조건이 곧 동시성 처리(검사와 변경이 한 문장). 갈림길은 "판단에 필요한 정보가 그 행 안에 다 있나". RETURNING·실패 원인은 갈아탈 이유가 아님
- 낙관적 락 = version을 묻는 조건부 UPDATE. 유일한 자리는 읽기와 쓰기가 다른 요청에 걸칠 때
- 공정성: DB 행 잠금 대기열은 FIFO(비관·원자적), 낙관·스핀·RLock은 불공정. 공정성은 처리량을 대가로
- 스핀 vs pub/sub: 둘 다 스레드는 블로킹, 차이는 넘겨받는 시점과 Redis 부하 (테스트 시간 기준 스핀이 2~4배 느림, 측정은 k6로)

**버그·트러블슈팅**
- Kotlin init에서 `changers.keys`가 생성자 파라미터를 가리킴 → `this.changers.keys`
- Redisson `Config.lazyInitialization`은 Kotlin 속성으로 안 보임(세터가 Config를 반환) → `setLazyInitialization(true)`
- 개발자 초안 `while(true) { if (tryLock) block() }`: 무한 루프·결과 버림 → tryLock 한 번 + 실패면 예외. 전략 확인이 없어 모든 전략에서 Redis를 탐 → REDIS 계열만

**다음**
- k6: core 배포 확인 → 시나리오(핫 계좌·분산 계좌, constant-arrival-rate) → 클러스터 안 k6 파드로 전략 6개 표(정합성·성공·실패 종류·TPS·p99·최대 지연·재시도·Redis 명령 수)
- `docs/experiments/week-07-locks.md`, 7주 차 체크
- 후속: 락 대기 503·재시도 소진 500을 transfer가 어떻게 분류하나, PESSIMISTIC lock_timeout
- 개발자: `docs/learning/concurrency.md` 요약 (중요), 11/20 공부 자료 요약

---

## 2026-11-23 (낮) · 원격 접속(Tailscale) + 7주 차 k6 락 비교 (7주 차 완료)

**한 일**
- Tailscale: bank-cp3를 서브넷 라우터(`172.30.1.0/24`)로, 맥북은 앱. 카페(`192.168.219.0/24`)에서 직접 연결 21ms, 꺼진 클러스터를 밖에서 켜기 성공(노드 6대 Ready, 앱 18개 Healthy)
- `cluster-up.sh` WOL은 항상 노트북이 보냄, `cluster-status.sh`도 노트북 경유(노트북 먼저 → ping → `ssh -J`), 컨트롤 플레인 꺼져 있으면 kubectl 건너뜀(22초 → 9초)
- k6 락 비교: 클러스터 안 k6 Job 실행기, 회차마다 새 계좌, DB 정합성·Redis 명령 종류별 기록. 전략 6개 × spread 200건/초 + hot 150/300/500건/초 (`docs/experiments/week-07-locks.md`)
- `RedisAccountLocks`: 풀기 전 `HEXISTS` 확인 제거, `IllegalMonitorStateException`으로 받기 + 테스트 (REDIS·REDIS_SPIN)
- 문서: `tailscale-and-nat.md`, `io-and-event-loop.md` 8절(락 한 번 명령 13개·왕복 4번, jar 확인), `2026-11-23.md` 공부 자료, pitfalls 35·36, `concurrency.md` 비교표에 k6 수치

**결정 (개발자)**
- 외부 공개는 당분간 Tailscale(본인만), 시연용 공개는 나중에 Cloudflare Tunnel + Access
- 클러스터 켜기·상태 확인은 항상 노트북 경유
- k6는 core 직접(락 차이만), hot·spread 섞지 않음, 잔액 넉넉히 (Claude 추천을 개발자가 채택. 처음엔 "시나리오가 너무 다양하다")
- 1차 hot이 경합을 못 만들어 다시 측정, `HEXISTS` 제거
- 7주 차를 닫고 8주 차로 (판독·해결책 선택·요약은 병행). 8주 차 설계는 다른 세션에서 시작 (`docs/design-drills/08-unknown-resolution.md`)

**버그·트러블슈팅**
- "network is unreachable": Tailscale이 아니라 클러스터가 꺼져 있던 것 (서브넷 라우터가 ARP 실패를 돌려줌)
- `cluster-up.sh`가 두 번 돈 것으로 보이는 proxmox2 "시작할 VM 없음" → 막 부팅한 호스트에서 `qm list` 실패를 빈 목록으로 읽는 구멍을 재시도로 막음
- 실행기: 수신 계좌에 0원 입금(400), k6가 죽었는데 "정합성 ✅" 출력 → 송금 0건이면 "판정 불가"
- hot 50건/초 균일 도착 = 경합 없음 (pitfalls 36)
- REDIS 계열 300·500건/초에서 core OOMKilled 4회 (limit 512Mi) → 원인 판독은 개발자 (실험 기록 판독 1)
- macOS `strings`가 class 파일을 Mach-O fat 파일로 오인 → 파이썬으로 상수 문자열 추출

**다음**
- 8주 차: 결과 불명 해소·일일 대사 설계 리뷰 (다른 세션에서 시작한 design-drills 08) → ADR → 구현. 후속으로 503·500(재시도 소진) 분류 계약
- 개발자: 실험 판독 5개 + 해결책 선택, `2026-11-23.md`·`concurrency.md` 요약
- `HEXISTS` 제거 배포 후 명령 수 재측정(송금당 26 → 24 기대)

## 2026-11-23 (밤) ~ 24 · 9주 차: 분산 트레이싱 (Tempo · Collector · 에이전트, Outbox 잇기)

**한 일**
- Tempo(3.1.1, 단일 바이너리, 72h)·OTel Collector(0.163.2)를 Argo CD로, 공통 차트에 에이전트 2.33.1(init 컨테이너 + `JDK_JAVA_OPTIONS`), Grafana에 Tempo 데이터 소스
- Collector에서 프로브·`/actuator/**` 잡음 버리기, 지표·로그 파이프라인 제거
- Outbox에서 끊긴 트레이스 잇기: outbox 행에 `traceparent`(V7), 릴레이가 행마다 그 요청의 자식 구간 → 송금 한 건이 gateway부터 notification까지 span 51개 한 트레이스 (`docs/infra/README.md` 트레이싱)
- 문서: `observability.md` 5-0(부품·AWS·Datadog, Outbox 잇기, 벤더를 바꿔도 남는 설계), pitfalls 38·39, story-arcs 7번 줄기

**결정 (개발자)**
- 트레이싱 부품 전부 설치, 프로브도 제외
- Outbox: 행에 문맥을 심고 "건건이 자식으로" (개발자 직관 = 행마다 자식, 묶음 구간만 링크). `opentelemetry-api` 추가 승인
- 관측성 배관은 설계 퀴즈 없이 Claude가 구현하고 코드로 설명 ("설계 문제라기보다 너가 잘하는 거")

**버그·트러블슈팅**
- Argo CD `valuesObject`가 `null`을 버려 Collector 기본값이 남음 → 문자열 `values` (pitfalls 38)
- Tempo 차트 Service 템플릿이 jaeger 수신기를 전제 → `null`로 지우면 렌더링 깨짐, 남겨 둠
- Collector 지표 포트 8888은 파드 IP에만 열려 port-forward 불가 → Tempo 쪽에서 확인
- 에이전트 붙인 뒤 유휴 메모리 +70~90Mi → limit 640Mi
- 집 랜선이 빠져 proxmox 링크 다운 (전원 문제로 오해했다가 정정)

**다음**
- 행 0건 릴레이 회차의 빈 트레이스 정리, 느린 송금 트레이스 읽기 연습, 로그에 traceId (9주 차 5단계)
- 8주 차 손 코딩은 개발자 (Flyway 다음 번호는 V8)

## 2026-11-25 · 8주 차: 타행 결과 불명 해소(2부) 완료, 일일 대사 설계(ADR-0006) + Garage·대사 서비스 뼈대

**한 일**
- 타행 해소: bank-mock 처리 상한(10s) + 조회 API + 처리·응답 지연 스위치, 상태 이름 `WITHDRAW_CANCEL_REQUESTED`(transfer V8, 클러스터 적용), 외부 조회·core 출금 조회 포트, 홉별 기준 시각으로 멈춘 타행 잡기(SKIP LOCKED), 상태별 Resolver(개발자) + 건마다 한 트랜잭션 유스케이스·스케줄러. 재현 테스트 3개 초록, transfer 테스트 166개
- 대사 설계: 설계 연습 09 결정 1~18 → ADR-0006 초안. 질답 `reconciliation.md`(시산표, 양방향, 정산 파일, 날짜 경계, 100만 건, 수집 방법 4가지), `audit-and-history.md`(상태 이력), `concurrency.md` 8절(긴 트랜잭션의 비용)
- Garage v2.6.0 단일 노드(버킷 `daily-extracts`, 유휴 5Mi), `reconciliation` 서비스 뼈대(전용 DB, 배포 전략 Recreate, 공통 차트에 strategy 선택), AWS SDK v2 S3 추가(승인). push → CI 성공 → 클러스터 Healthy

**결정 (개발자)**
- 타행 해소 트랜잭션: 잠금은 쥐고 **커밋은 건마다** (긴 트랜잭션의 문제는 잠금·커넥션이 아니라 커밋 단위. 청크를 키워도 빨라지지 않는다)
- 계속 실패하는 보상: 횟수가 아니라 **응답으로** 장애(재시도)·버그(수작업 대기 상태)를 가른다. 구현은 대사 뒤
- 대사: **별도 서비스**, **일자별 추출 파일**(증거) + 완료 이벤트(인박스), 우리 날짜 = `createdAt`, 양쪽 이월 1일, 상태별 판정표(개발자 작성), 정상 건은 요약만, 전부 아니면 전무, 저장소 Garage(MinIO 커뮤니티판 배포 중단)
- 분담: R-1 Garage·R-3 모듈/DB/배포는 Claude, **R-2 추출·R-3 인박스·테이블·R-4 대사 본체·R-5 경보는 개발자**

**버그·트러블슈팅**
- Resolver 재귀가 다음 단계로 안 넘어감 → 도메인 메서드가 같은 객체를 바꿔 전후 비교가 늘 같음 (전 상태를 값으로 떠 둠)
- 예외 나는 송금 하나가 회차마다 맨 앞에 잡혀 뒤를 다 막음 (head-of-line, 재현 테스트에서 실제로 봄) → 보류 항목
- Garage는 `If-None-Match: *`를 무시하고 덮어씀, 버전 관리도 없음 → 덮어쓰기 방지는 앱이 (키를 매번 새로, 이벤트가 키·체크섬을 가리킴)

**오후~저녁: 대사 구현 (개발자 요청으로 Claude가 전부 구현)**
- 추출 계약을 질문으로 다 정하다가 개발자가 "뼈대부터 만들고 필요한 데이터는 그때그때"를 원해 방향 전환 → 남은 세부(재추출 시 마지막 추출 사용, 추출에 그날 바뀐 송금도 실음, 메모리 판정)는 Claude가 정하고 결정 21·22로 기록
- 외부 내역 수신 주체 정정 (개발자 지적): 외부는 우리 저장소·Kafka에 쓰지 않는다 → 대사 서비스의 수신 작업(대외계 역할), bank-mock은 "기관 형식" 정산 파일 API (결정 19)
- core·transfer 일자별 추출(CSV → Garage → 완료 이벤트, core의 첫 Kafka 발행), 대사 서비스 인박스·회차·판정·이월·경보·시산표, 외부 쌍(판정표), 지표·Prometheus 경보 규칙. 대사 테스트 34개
- 클러스터: 오늘 265건 전부 정상 (합계로는 40억 vs 2만 8천 원 = 실패 송금은 전표가 없어야 정상). bank-mock 스위치(응답 5s + 조회 거짓 "없음")로 **이중 지급을 일으키고 대사가 DOUBLE_PAYMENT로 잡음**, `ReconciliationMoneyLoss` firing (`docs/experiments/week-08-reconciliation.md`)

**버그·트러블슈팅 (오후)**
- Garage가 AWS SDK 업로드를 `Invalid payload signature`로 거절 → 진짜 Garage 컨테이너 테스트로 재현, 청크 서명·체크섬 트레일러 끔 (pitfalls 42)
- KDoc에 `/transfers/**`를 쓰니 Kotlin 중첩 주석으로 읽혀 컴파일 실패
- bank-mock 배포 재시작으로 그날 처리 기록을 잃어 타행 12건이 외부 파일에서 빠짐 → MISSING_EXTERNAL 이월 (결정 6 "가짜의 한계"가 실제로)

**저녁: 추출 흘려 쓰기 (개발자 지적 "100만 건이면?")**
- 처음 추출은 리스트 → 문자열 → 바이트로 같은 데이터를 여러 벌 들고 있었다 → DB 커서(fetchSize 1,000 + 읽기 전용 트랜잭션) → 줄마다 임시 파일 + 건수·합계·SHA-256 누적 → 파일째 업로드
- 30만 건 테스트: 커서 없음 91MB / 있음 20MB 미만. 첫 테스트는 "첫 줄 vs 마지막 줄" 비교라 커서를 빼도 통과 → 기준을 "조회 전"으로 (story-arcs 5)
- 클러스터: transfer 391건 ↔ core 전표 392건 전부 정상

**8주 차 닫음 (개발자 결정)**: 남은 것은 CLAUDE.md 백로그 "8주 차에서 넘긴 것"으로

**다음**
- 9주 차 남은 것: 트레이싱 판독 연습 1회 (bank-mock 지연 → 느린 송금 트레이스, 플레이북 1-1), RED 대시보드 같이 읽기
- 내일 대사에서 오늘 MISSING_EXTERNAL 12건이 경보로 올라가는지 확인 (bank-mock 메모리 한계)
- ADR-0006 면접 30초 답변 (개발자)

---

## 이력서 소재 (계속 갱신)

> 아직 "결과 수치"가 있는 실험은 없다. Phase 2(6~8주 차)부터 재현 → 해결 → 전후 수치가 쌓인다.

**아키텍처 / 설계**
- 계정계 코어 모놀리스 + 주변 MSA로 서비스 경계 설계, 분산 경계를 실제로 갈라지는 지점(채널↔코어, 코어↔외부 결제망)에 배치 (ADR-0001)
- 서비스 성격에 따라 아키텍처 선택: 트랜잭션·락 중심 코어는 JPA 엔티티 도메인, 외부 연동 중심 송금 서비스는 헥사고날
- 돈이 움직이는 사가는 오케스트레이션(상태 한 곳, 결과 불명이 드러남), 결과 알림은 코레오그래피로 흐름 성격에 따라 구분해 적용, 같은 흐름을 두 방식으로 비교해 "커밋 후 발행 전 장애"에서의 차이를 정리

**인프라 / 플랫폼**
- 물리 호스트 2대(Proxmox) + 중재자 노드로 확장, 합의 시스템의 과반수·장애 영역 원리에 따라 배치 결정 (2대 클러스터 금지)
- Terraform을 호스트별 모듈로 리팩터링하며 운영 중인 VM 재생성을 막음 (`moved` 블록, 템플릿 렌더 결과 비교, plan 0 destroy 확인), 노드에 장애 영역(zone) 라벨
- etcd 디스크 적합성을 fio로 실측(fdatasync p99)해 노드 역할 배치에 반영
- 온프레미스(Proxmox) 쿠버네티스 클러스터를 Terraform으로 코드화, 빈 하이퍼바이저에서 약 2분 만에 재현
- GitOps(Argo CD) + GitHub Actions로 push → 배포 자동화, 배포 이력을 Git 커밋으로 추적
- 공통 Helm 차트로 서비스 배포 표준화 (프로브, 메모리 limit, 무중단 롤링, graceful shutdown, 비root)
- CloudNativePG로 서비스별 PostgreSQL 운영, 비밀번호는 Secret 주입

**금융 도메인**
- 복식부기 원장·결과 불명·대사 등 "돈이 움직이는 방식" 중심으로 도메인 범위 설계 (`docs/domain/README.md`)
- 잔액 변경을 도메인 메서드로 제한, 음수 금액·잔고 부족을 업무 예외로 거절하고 경계값 테스트로 고정
- 복식부기 원장과 잔액 변경을 한 트랜잭션으로 묶고, 원장 기록 후 실패해도 함께 롤백됨을 통합 테스트로 증명
- 멱등 키 기반 중복 거래 방지: 같은 키 동시 요청을 DB UNIQUE + 트랜잭션 밖 재시도로 처리, 경합을 결정적으로 재현하는 테스트(CountDownLatch + spy)로 검증
- 서비스 간 송금 호출에서 DB 트랜잭션을 분리(처리중 커밋 → 트랜잭션 밖 원격 호출 → 결과 커밋)해 커넥션 점유를 없애고, 호출 중 트랜잭션 부재를 테스트로 증명
- 원격 호출 결과를 완료·거절·결과 불명으로 분류해 "응답 없음 = 실패" 오판으로 인한 이중 송금 경로 제거 (타임아웃·5xx·멱등 충돌은 결과 불명 → 같은 송금 ID 재전송/상태 조회로 확정)
- 고객 멱등 키를 송금 레코드에 통합해 키 저장과 송금 생성을 원자화, 처리 중·결과 불명 상태의 재요청 규칙 설계
- 타행 이체를 오케스트레이션 사가로 설계: 되돌릴 수 없는 외부 송금을 피벗으로 두고 앞 단계는 보상·뒤 단계는 재시도로 분리, "보냈을 수도 있는" 상태를 따로 두어 결과 불명에서의 보상(이중 지급) 차단
- 보상(출금 취소)을 원래 전표 참조 + DB 유일 제약(자연 키)으로 구현해 중복·동시 취소와 과다 환불을 구조적으로 차단, 미정산 임시 계정으로 복식부기 정합성 유지
- 사가 단계마다 원격 호출 전에 의도 상태를 커밋해 "이전 상태 = 확실히 안 보냄"을 보장, 결과 불명에서는 보상하지 않음을 테스트로 고정 (호출 순간 의도 커밋·트랜잭션 부재 검증)

**보안 / API**
- Spring Cloud Gateway(WebFlux)에서 JWT 인증, 들어온 사용자 헤더를 지우고 토큰 값으로 덮어써 신원 위조 차단. 위조 헤더·alg=none·서명 변조·만료 등 공격 시나리오를 테스트로 고정
- BOLA(객체 수준 인가 실패)를 재현해 막음: 소유자 검사를 출금과 같은 트랜잭션 안에 두어 TOCTOU 제거, 남의 자원은 404로 응답해 존재 여부 누출 방지
- 멱등 재요청은 권한을 다시 판정하지 않도록 순서를 설계해 "돈은 빠졌는데 실패로 보이는" 경로 제거
- 고객 멱등 키를 사용자 범위로 바꿔 다른 사용자의 거래 정보가 보이는 문제 제거 (운영 데이터 백필 포함 무중단 마이그레이션)

**메시징**
- Kafka(Strimzi, KRaft)로 송금 결과 이벤트 발행·구독, 상태 전이 시점에만 발행해 재요청 중복 발행을 구조적으로 제거
- Transactional Outbox로 비동기 경계에서 끊기는 분산 트레이스를 W3C traceparent 저장·복원으로 이어, 요청부터 이벤트 소비까지 한 트레이스로 추적 (묶음 발행은 행 단위 자식 구간 + 링크, 벤더 중립 OTel API)
- Kafka 이벤트 소비 장애를 실제 Kafka로 재현하고 대응: 중복 수신 시 알림 이중 발송(이벤트 ID 유일 제약으로 차단), 깨진 메시지 하나가 파티션 전체를 멈추는 문제(격리 후 건너뛰기)
  - (보류) 이벤트 스키마 호환성 규칙: 지금은 역직렬화 설정 한 줄이라 약하다. 6주 차 이후 실제로 스키마를 바꿔 볼 때 "추가만 허용, 변경은 버전 업 + 소비자 먼저 배포" 규칙으로 쓴다
- 프로듀서 블로킹 타임아웃(`max.block.ms`)을 조정해 브로커 장애가 송금 API로 전파되는 경로 차단
- 커밋과 발행 사이 이벤트 유실을 테스트로 재현하고 Transactional Outbox로 해결: Kafka 브로커 31초 중단 중 완료된 송금 5건의 이벤트를 복구 3초 안에 100% 전달, Kafka 장애가 송금 API에 전파되지 않음
- Outbox 폴링 릴레이를 `FOR UPDATE SKIP LOCKED`로 다중 인스턴스에서 중복 전송 없이 병렬 처리, ack 확인 후에만 발행 표시해 "실패를 삼켜 다시 유실"되는 경로 차단
- 컨슈머 오프셋 되감기로 중복 수신을 클러스터에서 재현, 이벤트 ID 유일 제약 기반 멱등 컨슈머가 재수신 5건을 모두 걸러 알림 중복 0 확인
- 발행 타임아웃이 미발행이 아님(브로커가 늦게 기록)을 재현 중 발견해, at-least-once 전제와 eventId 고정(재시도 시 같은 ID)으로 설계
- 락 전략 6가지(락 없음·낙관·비관·조건부 UPDATE·Redis pub/sub·스핀)를 실행 중 스위치로 바꿀 수 있게 구현, lost update·데드락을 테스트로 재현하고 핫 계좌에서 전략별 성공·실패 양상을 비교
- 락 전략 6가지를 k6로 클러스터 안에서 비교(핫 계좌 150~500건/초, 분산 200건/초, 회차마다 격리된 계좌와 원장 대사로 정합성 판정): 락 없음은 150건/초부터 잔액 불일치(원장은 정확), 조건부 UPDATE는 500건/초에서도 p99 7ms·실패 0, 비관적 락은 줄 서기로 p50 1.26s, 낙관적 락은 실패 39%, Redis 분산 락은 300건/초부터 서비스 OOM으로 붕괴함을 측정
- 균일 도착 부하가 경합을 만들지 못해 "락 없이도 정합"으로 보이는 실험 오류를 대조군으로 발견하고 부하를 행 처리 한계 이상으로 재설계
- Redis 분산 락 한 번의 비용을 명령 단위로 분해(왕복 4번·명령 13개, 라이브러리 바이트코드 확인)하고, 효과 없는 사전 확인(TOCTOU)을 제거해 락당 왕복 4번 → 3번 (재측정 예정)
- 타행 이체 사가의 결과 불명 해소: 외부 은행과 "처리 상한 + 조회" 계약을 두어 상한 뒤의 "없음"을 믿고 출금을 취소, 원격 호출 두 번이 붙는 해소를 "잠금은 유지·커밋은 건마다"로 설계해 긴 트랜잭션의 비용(가시성 지연·통째 재처리·종료 시간 초과)을 제거
- 일일 대사 서비스를 설계·구현 (ADR-0006): 각 서비스의 일자별 추출 파일(S3 호환 저장소, 체크섬·건수·합계 trailer)과 외부 정산 파일을 양방향 건별 대사, 자정 경계·시계 차이는 이월, 끝 상태별 판정표로 이중 지급·고객 손실을 즉시 경보. 외부 조회 계약 위반으로 이중 지급을 일부러 일으켜 내부 원장은 정확한데 대사만 잡아내는 것을 클러스터에서 확인 (Prometheus 경보 firing)
- 실제 데이터 265건 대사에서 총액은 40억 원 차이였지만 건별로는 전부 정상(실패 송금은 전표가 없어야 정상)임을 보여 총액 대사의 한계를 수치로 확인
- Redis 분산 락을 트랜잭션 안에서 풀면 커밋 전 값이 읽혀 잔액이 깨짐을 재현, 잠금이 트랜잭션 바깥을 감싸도록 모듈 경계를 설계하고 DB 버전 검사를 안전망으로 남겨 "재시도 0"을 락 정상 동작의 지표로 사용

**관측성**
- Prometheus·Grafana(kube-prometheus-stack)를 GitOps로 구성하고 대시보드를 코드로 관리, 수집 대상은 PodMonitor 선언으로 자동 등록 (서비스·Kafka 컨슈머 랙·PostgreSQL)
- actuator를 관리 포트로 분리해 지표 엔드포인트의 외부 노출을 막고, 보안 필터가 관리 포트에도 적용되는 함정을 테스트로 발견·고정
- k6 부하 테스트(고객 위치와 서버 지표를 나란히 측정)로 처리 한계를 찾고, 대시보드로 붕괴 연쇄를 추적: DB 커넥션 풀 고갈 → 대기 스레드 증가 → 힙 밖 메모리 증가 → 컨테이너 OOMKilled → 앞단 게이트웨이까지 전파 (힙은 여유였음을 확인해 JVM 힙 문제가 아님을 구분)
- 실측 기반 리소스 라이트사이징: 측정 없이 정한 초기 메모리 request가 평소 부하에서 이미 초과됨을 발견, CPU(압축 가능)와 메모리(압축 불가)의 특성에 맞춰 request·limit 정책 재설정

**운영 / 트러블슈팅**
- S3 호환 저장소(Garage)가 AWS SDK 새 기본값(청크 서명·체크섬 트레일러)을 거부해 운영에서만 업로드가 깨지는 문제를 진짜 저장소 컨테이너 테스트로 재현·수정
- 공유기 포트 개방 없이 Tailscale 서브넷 라우터로 원격 운영 환경 구성, L2 브로드캐스트(WOL)는 집 안 상시 노드가 대신 발신해 밖에서도 클러스터 전원 제어
- 재시작 때만 실패하는 Flyway 장애(PostgreSQL search_path와 스키마 이름 충돌)를 운영과 같은 계정 조건의 테스트로 재현·수정

**코드 품질**
- 값 객체(Kotlin value class)로 입력 검증을 타입에 담아 검증 누락 경로를 컴파일 단계에서 제거, 400(클라이언트가 고칠 수 있음)과 500(서버 버그)을 계층별로 구분
