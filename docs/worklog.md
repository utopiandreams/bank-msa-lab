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

---

## 이력서 소재 (계속 갱신)

> 아직 "결과 수치"가 있는 실험은 없다. Phase 2(6~8주 차)부터 재현 → 해결 → 전후 수치가 쌓인다.

**아키텍처 / 설계**
- 계정계 코어 모놀리스 + 주변 MSA로 서비스 경계 설계, 분산 경계를 실제로 갈라지는 지점(채널↔코어, 코어↔외부 결제망)에 배치 (ADR-0001)
- 서비스 성격에 따라 아키텍처 선택: 트랜잭션·락 중심 코어는 JPA 엔티티 도메인, 외부 연동 중심 송금 서비스는 헥사고날

**인프라 / 플랫폼**
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

**보안 / API**
- Spring Cloud Gateway(WebFlux)에서 JWT 인증, 들어온 사용자 헤더를 지우고 토큰 값으로 덮어써 신원 위조 차단. 위조 헤더·alg=none·서명 변조·만료 등 공격 시나리오를 테스트로 고정
- BOLA(객체 수준 인가 실패)를 재현해 막음: 소유자 검사를 출금과 같은 트랜잭션 안에 두어 TOCTOU 제거, 남의 자원은 404로 응답해 존재 여부 누출 방지
- 멱등 재요청은 권한을 다시 판정하지 않도록 순서를 설계해 "돈은 빠졌는데 실패로 보이는" 경로 제거
- 고객 멱등 키를 사용자 범위로 바꿔 다른 사용자의 거래 정보가 보이는 문제 제거 (운영 데이터 백필 포함 무중단 마이그레이션)

**운영 / 트러블슈팅**
- 재시작 때만 실패하는 Flyway 장애(PostgreSQL search_path와 스키마 이름 충돌)를 운영과 같은 계정 조건의 테스트로 재현·수정

**코드 품질**
- 값 객체(Kotlin value class)로 입력 검증을 타입에 담아 검증 누락 경로를 컴파일 단계에서 제거, 400(클라이언트가 고칠 수 있음)과 500(서버 버그)을 계층별로 구분
