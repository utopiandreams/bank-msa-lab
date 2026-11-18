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

## 이력서 소재 (계속 갱신)

> 아직 "결과 수치"가 있는 실험은 없다. Phase 2(6~8주 차)부터 재현 → 해결 → 전후 수치가 쌓인다.

**아키텍처 / 설계**
- 계정계 코어 모놀리스 + 주변 MSA로 서비스 경계 설계, 분산 경계를 실제로 갈라지는 지점(채널↔코어, 코어↔외부 결제망)에 배치 (ADR-0001)
- 서비스 성격에 따라 아키텍처 선택: 트랜잭션·락 중심 코어는 JPA 엔티티 도메인, 외부 연동 중심 송금 서비스는 헥사고날

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

**보안 / API**
- Spring Cloud Gateway(WebFlux)에서 JWT 인증, 들어온 사용자 헤더를 지우고 토큰 값으로 덮어써 신원 위조 차단. 위조 헤더·alg=none·서명 변조·만료 등 공격 시나리오를 테스트로 고정
- BOLA(객체 수준 인가 실패)를 재현해 막음: 소유자 검사를 출금과 같은 트랜잭션 안에 두어 TOCTOU 제거, 남의 자원은 404로 응답해 존재 여부 누출 방지
- 멱등 재요청은 권한을 다시 판정하지 않도록 순서를 설계해 "돈은 빠졌는데 실패로 보이는" 경로 제거
- 고객 멱등 키를 사용자 범위로 바꿔 다른 사용자의 거래 정보가 보이는 문제 제거 (운영 데이터 백필 포함 무중단 마이그레이션)

**메시징**
- Kafka(Strimzi, KRaft)로 송금 결과 이벤트 발행·구독, 상태 전이 시점에만 발행해 재요청 중복 발행을 구조적으로 제거
- Kafka 이벤트 소비 장애를 실제 Kafka로 재현하고 대응: 중복 수신 시 알림 이중 발송(이벤트 ID 유일 제약으로 차단), 깨진 메시지 하나가 파티션 전체를 멈추는 문제(격리 후 건너뛰기)
  - (보류) 이벤트 스키마 호환성 규칙: 지금은 역직렬화 설정 한 줄이라 약하다. 6주 차 이후 실제로 스키마를 바꿔 볼 때 "추가만 허용, 변경은 버전 업 + 소비자 먼저 배포" 규칙으로 쓴다
- 프로듀서 블로킹 타임아웃(`max.block.ms`)을 조정해 브로커 장애가 송금 API로 전파되는 경로 차단

**관측성**
- Prometheus·Grafana(kube-prometheus-stack)를 GitOps로 구성하고 대시보드를 코드로 관리, 수집 대상은 PodMonitor 선언으로 자동 등록 (서비스·Kafka 컨슈머 랙·PostgreSQL)
- actuator를 관리 포트로 분리해 지표 엔드포인트의 외부 노출을 막고, 보안 필터가 관리 포트에도 적용되는 함정을 테스트로 발견·고정
- k6 부하 테스트(고객 위치와 서버 지표를 나란히 측정)로 처리 한계를 찾고, 대시보드로 붕괴 연쇄를 추적: DB 커넥션 풀 고갈 → 대기 스레드 증가 → 힙 밖 메모리 증가 → 컨테이너 OOMKilled → 앞단 게이트웨이까지 전파 (힙은 여유였음을 확인해 JVM 힙 문제가 아님을 구분)
- 실측 기반 리소스 라이트사이징: 측정 없이 정한 초기 메모리 request가 평소 부하에서 이미 초과됨을 발견, CPU(압축 가능)와 메모리(압축 불가)의 특성에 맞춰 request·limit 정책 재설정

**운영 / 트러블슈팅**
- 재시작 때만 실패하는 Flyway 장애(PostgreSQL search_path와 스키마 이름 충돌)를 운영과 같은 계정 조건의 테스트로 재현·수정

**코드 품질**
- 값 객체(Kotlin value class)로 입력 검증을 타입에 담아 검증 누락 경로를 컴파일 단계에서 제거, 400(클라이언트가 고칠 수 있음)과 500(서버 버그)을 계층별로 구분
