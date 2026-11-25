# 경보(Alert) 하나를 처음부터 끝까지: 무엇을 세고 → 언제 울리고 → 누구에게 → 어디서 보나

> 2026-11-25 개발자 질문: "어떤 경우에 얼럿을 하고 싶으면 어떻게 설정해야 해? 코드 레벨에서는 어떻게 하고, 어떻게 보여주고 어떻게 전달할지"
> 판독(대시보드를 읽는 법)은 `observability.md`, 장면별 클릭 경로는 `monitoring-playbook.md`. 이 문서는 **경보를 만드는 법**.

## 0. 큰 그림: 다섯 층

| 층 | 질문 | 누가 | 이 프로젝트의 파일 |
| --- | --- | --- | --- |
| ① 지표 | 무엇을 셀까 | **앱 코드** (Micrometer) | `ReconMetrics.kt`, `OutboxBacklogMetrics.kt` |
| ② 수집 | 숫자를 가져오기 | Prometheus (PodMonitor로 자동) | 공통 차트가 서비스마다 PodMonitor 생성 |
| ③ 규칙 | 언제 울릴까 | **PrometheusRule** (YAML, Git) | `deploy/platform/monitoring/*-rules.yaml` |
| ④ 전달 | 누구에게, 어떻게 | **Alertmanager** 설정 (YAML, Git) | `deploy/argocd/apps/kube-prometheus-stack.yaml`의 `alertmanager.config` |
| ⑤ 보기 | 어디서 확인 | Grafana · Alertmanager UI · (13주 차) 컨트롤 패널 | `deploy/grafana/dashboards/` |

```
앱(①) ──긁기──▶ Prometheus(②) ──15초마다 규칙 평가(③)──▶ Alertmanager(④) ──▶ 📱 Discord·Telegram·전화
                    ▲                                          ▲
                    └──────── Grafana(⑤)는 둘 다 들여다보기만 ───┘
```

- 💡 경보는 **"보러 가는 것"이 아니라 "찾아오는 것"**이다. 화면을 열어야 보이는 경보는 새벽 3시에 아무도 모른다. ④의 받는 곳이 비어 있으면 경보 시스템은 반쯤만 있는 것이다
- 💡 Grafana에도 자체 경보 엔진(Grafana Alerting)이 있다. 이 프로젝트는 ③④를 Prometheus 쪽에 둔다: Git에 남고(Grafana는 영속 볼륨이 없어 화면 설정이 사라진다), **Grafana가 죽어도 경보는 간다**. Grafana Alerting은 Loki 로그 기반 경보처럼 Prometheus 밖 데이터에 쓸 때

---

## 1. 만들기 전에: "이게 울리면 누가 무엇을 하나?"

경보마다 이 질문에 답이 없으면 경보가 아니라 **대시보드 패널**로 둔다.

| 구분 | 뜻 | 예 | 받는 곳 |
| --- | --- | --- | --- |
| **page** (critical) | 지금 사람이 움직여야 한다. 자는 사람을 깨운다 | 이중 지급, 송금 에러율 급증, 결과 불명이 오래 안 풀림 | 휴대폰 푸시 / 전화 |
| **ticket** (warning) | 오늘 안에 보면 된다 | 디스크 80%, 페이지 폴트, 인증서 30일 남음 | 채팅 채널 |
| 경보 아님 | 보기만 할 숫자 | CPU 70%, GC 횟수 | 대시보드 |

- 💡 **증상에 걸고, 원인은 대시보드로.** "CPU 90%"는 원인 후보일 뿐 고객이 아프다는 뜻이 아니다 (`observability.md` 7-0). "송금 실패율 5%", "결과 불명이 10분째" 같은 **고객이 느끼는 것**에 건다. 원인 경보를 많이 걸면 **경보 피로**(alert fatigue): 매일 울리니 아무도 안 보고, 진짜 장애도 묻힌다
- 💡 critical이 일주일에 몇 번씩 울리면 기준이 틀린 것. 울렸는데 할 일이 없었다면 그 경보를 지우거나 warning으로 내린다

---

## 2. ① 앱: 무엇을 셀까 (코드)

Prometheus는 **숫자만** 안다. 경보를 걸고 싶은 상황을 숫자로 만드는 게 앱의 일이다.

### 지표 종류 고르기

| 종류 | 성질 | 경보로 쓰는 법 | 예 |
| --- | --- | --- | --- |
| **Counter** | 계속 늘기만 함 (재시작하면 0부터) | `rate()`로 초당 몇 번 → 비율 | 송금 완료 수, 실패 수 |
| **Gauge** | 지금 값 (오르내림) | 그대로 임계값 비교 | 미발행 outbox 수, 결과 불명 송금 수, 큐 길이 |
| **Timer** (히스토그램) | 걸린 시간의 분포 | `histogram_quantile` → p99 | API 지연, 외부 호출 지연 |
| **마지막 성공 시각** (Gauge) | epoch 초 | `time() - 값 > 기한` = **안 일어났다** 경보 | 배치 마지막 완료, 추출 마지막 도착 |

> 마지막 종류가 실무에서 특히 중요하다. "배치가 실패했다"는 로그가 남지만 **"배치가 아예 안 돌았다"**는 아무것도 남지 않는다. 그래서 받는 쪽이 "안 왔다"를 본다 = dead man's switch (대사의 `ReconciliationExtractMissing`)

### 코드 모양 (Kotlin, Micrometer)

```kotlin
// Counter: 일이 일어날 때마다 +1. 라벨(태그)로 나눠 센다
registry.counter("transfer.finished", "kind", "INTERBANK", "result", "CANCELLED").increment()
//  → Prometheus: transfer_finished_total{kind="INTERBANK", result="CANCELLED"}

// Gauge (DB를 세는 경우): 주기적으로 다시 센다 → 재시작해도 바로 맞고, 코드 경로를 빠뜨려 어긋날 일이 없다
// 실제 예: ReconMetrics(30초마다 SELECT count), OutboxBacklogMetrics(긁을 때 쿼리, 2초 재사용)
Gauge.builder("transfer.unknown.oldest.age") { repository.oldestUnknownAgeSeconds() }
    .baseUnit("seconds")
    .register(registry)

// Timer: 걸린 시간. p99를 쓰려면 application.yaml에서 percentiles-histogram을 켠다 (이 프로젝트는 켜 둠)
Timer.builder("transfer.resolution.duration").register(registry).record { resolver.resolveOne(id) }

// 마지막 성공 시각
private val lastSuccess = AtomicLong(0)
init { Gauge.builder("transfer.resolution.last.success.seconds", lastSuccess) { it.get().toDouble() }.register(registry) }
fun onRoundFinished() = lastSuccess.set(Instant.now().epochSecond)
```

### 함정

- **라벨에 ID를 넣지 않는다.** `transfer_id`, `account_id`, `user_id`를 라벨로 넣으면 값마다 새 시계열이 생겨 Prometheus가 터진다 (`observability.md` 4-6 카디널리티). 라벨은 값의 종류가 몇 개뿐인 것만: 상태, 종류, 결과
  - "어느 송금인가"는 경보 문구에서 DB·로그·트레이스로 찾아가게 한다 (설명에 조회 방법을 적는다)
- **드물게 일어나는 Counter는 0으로 미리 등록한다.** 처음 발생할 때 생기는 카운터는 첫 증가가 `rate()`에 안 잡힌다 (pitfalls 41)
- **지표가 아예 없으면 규칙은 평가조차 안 된다.** "값 > 0" 규칙은 지표가 사라지면 조용해진다 → 중요한 지표는 `absent()` 규칙을 같이 건다 (3절)
- 만들었으면 확인: `kubectl -n bank port-forward deploy/transfer 8081` → `curl localhost:8081/actuator/prometheus | grep transfer_` → Prometheus에서 같은 이름으로 조회

---

## 3. ③ 규칙: 언제 울릴까 (PrometheusRule)

`deploy/platform/monitoring/`에 YAML을 두면 Argo CD가 올리고 prometheus-operator가 Prometheus에 넣는다 (`monitoring-targets` 앱).

### 생김새 (예시: 결과 불명 송금이 오래 안 풀린다)

```yaml
apiVersion: monitoring.coreos.com/v1
kind: PrometheusRule
metadata:
  name: transfer
spec:
  groups:
    - name: transfer
      rules:
        - alert: TransferUnknownStuck                       # 경보 이름 (알림 제목, 묶기 기준)
          expr: max(transfer_unknown_oldest_age_seconds) > 600   # 참이 되는 동안 "울리는 중"
          for: 5m                                           # 5분 연속 참이어야 발송 (순간 튐 무시)
          labels:
            severity: critical                              # ④에서 받는 곳을 고르는 기준
            service: transfer
          annotations:                                      # 알림 본문 (사람이 읽는 것)
            summary: "결과 불명 송금이 {{ $value | humanizeDuration }}째 안 풀림"
            description: |
              해소 스케줄러가 멈췄거나 core·외부 은행 조회가 계속 실패한다.
              확인: transfer 로그 {app="transfer"} |= "resolution" / RED 대시보드 ④ 의존성
            runbook_url: https://github.com/.../docs/learning/monitoring-playbook.md#...
            dashboard: http://grafana.172.30.1.240.nip.io/d/bank-red
```

### expr 자주 쓰는 모양

| 상황 | 식 | 메모 |
| --- | --- | --- |
| 임계값 | `max(gauge) > 600` | 파드가 여럿이면 같은 DB를 세는 게이지는 `sum`이 아니라 `max` |
| 비율 (에러율) | `sum(rate(errors_total[5m])) / sum(rate(requests_total[5m])) > 0.05` | 분모가 0이면 결과가 비어 안 울림 = 트래픽 없을 땐 조용 |
| 안 일어났다 | `time() - last_success_seconds > 26*3600` | 배치·추출 (대사 규칙 그대로) |
| 지표가 사라졌다 | `absent(up{job="bank/transfer"})` | 서비스가 죽어 수집이 안 될 때 |
| 곧 찬다 | `predict_linear(disk_free[6h], 4*3600) < 0` | 4시간 뒤 디스크가 0이 될 기세 |
| SLO 소진 속도 | `(에러율 / (1 - 0.999)) > 14.4` (1시간·5분 창 둘 다) | 11주 차. "한 달 예산을 2일에 다 쓸 속도" |

- **`for`**: 짧으면 순간 튐에도 울리고(소음), 길면 늦게 안다. 돈 손실처럼 한 건도 안 되는 건 `for` 없이, 지연·비율은 5~15분
- **`keep_firing_for`**: 값이 경계에서 왔다 갔다 하면(flapping) 울림 → 해제 → 울림 알림이 쏟아진다. 해제를 몇 분 늦춘다
- **상태 3단계**: inactive(거짓) → **pending**(참인데 `for` 기다리는 중) → **firing**(발송). Prometheus UI Alerts 탭이나 Grafana Alerting → Alert rules에서 보인다

### 규칙도 테스트한다

- **promtool 단위 테스트**: 가짜 시계열을 주고 "이 시각에 이 경보가 울려야 한다"를 검사 (`promtool test rules`). 규칙도 코드라 CI에서 돌린다
- **화재 훈련**: 실제로 상황을 만들어 끝(📱)까지 오는지 본다. 이 프로젝트는 대사 이중 지급 재현 때 `ReconciliationMoneyLoss`가 실제로 울렸다 (받는 곳이 없어 화면에서만)

---

## 4. ④ 전달: 누구에게, 어떻게 (Alertmanager)

Prometheus는 울리는 중인 경보를 **전부, 계속** Alertmanager로 보낸다. 사람에게 보낼 모양으로 다듬는 건 Alertmanager의 일이다.

| 기능 | 하는 일 | 없으면 |
| --- | --- | --- |
| **묶기** (group_by) | 같은 원인의 경보 여러 개 → 알림 한 통 | 노드 하나 죽으면 파드 수십 개분의 알림 폭탄 |
| **라우팅** (route) | 라벨(severity 등)로 받는 곳 고르기 | 디스크 경고에 사람을 깨움 |
| **반복** (repeat_interval) | 안 풀렸으면 N시간마다 다시 | 한 번 놓치면 끝 |
| **억제** (inhibit_rules) | 상위 경보가 울리면 하위는 조용히 | "노드 다운"과 그 노드 위 모든 서비스 경보가 동시에 |
| **침묵** (silence) | 점검 중 잠깐 끄기 (UI·amtool) | 계획된 작업에도 호출 |
| **해제 알림** (send_resolved) | "풀렸다"도 보낸다 | 끝났는지 대시보드를 다시 봐야 함 |

### 설정 모양 (kube-prometheus-stack 값의 `alertmanager.config`)

```yaml
alertmanager:
  config:
    route:
      receiver: chat                       # 기본 받는 곳 (아래 어디에도 안 걸리면)
      group_by: [alertname, severity]      # 이 라벨이 같으면 한 통으로
      group_wait: 30s                      # 첫 경보 뒤 30초 모아서 보냄 (같이 터진 것까지 한 통에)
      group_interval: 5m                   # 같은 묶음에 새 경보가 붙으면 5분 뒤 갱신
      repeat_interval: 4h                  # 안 풀리면 4시간마다 다시
      routes:
        - matchers: [ alertname = "Watchdog" ]
          receiver: deadman                # 외부 감시 서비스로 (끊기면 그쪽이 사람을 부름)
          repeat_interval: 1m
        - matchers: [ severity = "critical" ]
          receiver: phone
          repeat_interval: 1h
    inhibit_rules:
      - source_matchers: [ severity = "critical" ]   # critical이 울리는 동안
        target_matchers: [ severity = "warning" ]    # 같은 alertname의 warning은 보내지 않음
        equal: [alertname]
    receivers:
      - name: chat
        discord_configs:
          - webhook_url_file: /etc/alertmanager/secrets/alert-webhooks/discord-warning   # Secret을 파일로 (Git에 URL 없음)
            send_resolved: true
      - name: phone
        telegram_configs:                  # 휴대폰 푸시가 확실
          - bot_token_file: /etc/alertmanager/secrets/alert-webhooks/telegram-token
            chat_id: 123456789
            send_resolved: true
      - name: deadman
        webhook_configs:
          - url_file: /etc/alertmanager/secrets/alert-webhooks/healthchecks-url
  alertmanagerSpec:
    secrets: [alert-webhooks]              # Secret을 /etc/alertmanager/secrets/alert-webhooks/ 아래 파일로 마운트
```

- 💡 **웹훅 URL·봇 토큰은 비밀값**이다 (누가 알면 우리 채널에 아무 말이나 보낼 수 있다). Secret으로 만들고 `*_file`로 읽는다 (`docs/infra/README.md`에 만드는 명령)
- 💡 설정 위치가 둘 있다: 전역 `alertmanager.config` (지금처럼 한 팀) / 네임스페이스별 `AlertmanagerConfig` 리소스 (팀마다 자기 라우팅을 따로 관리할 때)
- 💡 메시지 모양은 템플릿으로 바꾼다. 기본 문구는 영어 라벨 나열이라, summary · description · 대시보드 링크가 위로 오게 다듬는다

---

## 5. ⑤ 보기: 화면에서 어떻게 보이게 하나

경보의 1차 수단은 ④의 알림이고, 화면은 **이미 보고 있는 사람**을 위한 보조다.

| 어디 | 무엇 | 어떻게 |
| --- | --- | --- |
| Grafana Alerting → **Active notifications** | 지금 울리는 경보 묶음 | 위쪽 **Choose Alertmanager**를 `Alertmanager`로 (기본값 Grafana는 비어 있음) |
| Grafana Alerting → **Alert rules** | 규칙 목록과 상태(Normal·Pending·Firing) | 아래 **Data source-managed** 칸 |
| 대시보드 **Alert list 패널** | 대시보드 맨 위에 빨강·노랑 목록 | RED 대시보드 ①에 둘 수 있음 |
| 대시보드 **세로선 표시(annotation)** | 그래프 위에 "여기서 경보 시작" 선 | Prometheus의 `ALERTS{alertstate="firing"}` 시계열로 (경보도 시계열로 저장된다) |
| Alertmanager UI | 묶기·침묵 관리 | `kubectl -n monitoring port-forward svc/kps-alertmanager 9093` |
| 13주 차 컨트롤 패널 | 타임라인에 "14:02 경보 → 14:05 해제" | CLAUDE.md 9장 하단 타임라인 |

---

## 6. 한 장 요약: 새 경보를 추가하는 순서

1. **문장으로 쓴다**: "결과 불명 송금이 10분 넘게 안 풀리면, transfer 담당이 해소 스케줄러와 조회 대상을 확인한다" → 누가·무엇을 할지 없으면 대시보드 패널로
2. **숫자가 있나 본다**: Prometheus에서 조회. 없으면 앱에 지표 추가 (Counter·Gauge·Timer·마지막 성공 시각 중에서, 라벨에 ID 금지)
3. **규칙을 쓴다**: `deploy/platform/monitoring/<서비스>-rules.yaml`에 expr · for · severity · summary · description(확인 방법) · 대시보드 링크
4. **상황을 만들어 울려 본다**: pending → firing, 알림이 📱까지 오는지, 풀렸을 때 해제 알림까지
5. **플레이북에 장면을 남긴다**: 이 경보가 오면 어디를 어떤 순서로 보나 (`monitoring-playbook.md`)

---

## 이 프로젝트에 지금 있는 것 / 없는 것 (2026-11-25)

- ✅ 지표: 서비스 RED(자동), 대사 예외·마지막 도착(`ReconMetrics`), Outbox 적체(`OutboxBacklogMetrics`), JVM·DB·Kafka 랙·노드
- ✅ 규칙: 대사 4개(`reconciliation-rules.yaml`: 돈 손실 즉시 · 불일치 · 추출 26시간 · 지표 자체 없음), 차트 기본 규칙(노드·쿠버네티스, `NodeMemoryMajorPagesFaults` 등), RED 기록 규칙(`red-rules.yaml`, 계산만, 경보 아님)
- ✅ Alertmanager 켜짐 (2026-11-25)
- ❌ **받는 곳 없음** (null) → 개발자가 채널 선택
- ❌ 서비스 경보: 송금 에러율·p99 (11주 차 SLO와 함께), Outbox 적체, 결과 불명 체류

---

## 🎤 면접 꼬리 질문

- 경보를 무엇에 거나요? CPU 사용률에 경보를 거는 건 왜 별로인가요? (증상 vs 원인, 경보 피로)
- "배치가 안 돌았다"는 어떻게 알아채나요? (마지막 성공 시각 + 기한, dead man's switch, Watchdog)
- 노드 하나가 죽으면 알림이 100통 오는 문제는 어떻게 막나요? (group_by, inhibit_rules)
- `for: 5m`은 왜 두나요? 돈 손실 경보에는 왜 안 두나요?
- 경보 규칙은 어떻게 테스트하나요? (promtool test rules, 화재 훈련)
- 경보가 Grafana 화면에만 뜬다면 무엇이 문제인가요? (찾아와야 한다, Grafana가 죽으면 경보도 죽는다)

## 📎 정답지

- 실제 규칙: `deploy/platform/monitoring/reconciliation-rules.yaml` (dead man's switch · absent 포함), `red-rules.yaml`
- 실제 지표 코드: `services/reconciliation/.../ReconMetrics.kt`, `services/transfer/.../outbox/OutboxBacklogMetrics.kt`
- Alertmanager 켠 설정: `deploy/argocd/apps/kube-prometheus-stack.yaml`
- 판독: `observability.md` 1-3 · 1-4, 함정: `pitfalls.md` 41 (첫 에러는 rate에 안 잡힘)
