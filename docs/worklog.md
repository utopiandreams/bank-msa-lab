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

**코드 품질**
- 값 객체(Kotlin value class)로 입력 검증을 타입에 담아 검증 누락 경로를 컴파일 단계에서 제거, 400(클라이언트가 고칠 수 있음)과 500(서버 버그)을 계층별로 구분
