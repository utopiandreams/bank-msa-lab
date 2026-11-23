#!/usr/bin/env bash
# 7주 차 락 전략 비교 실행기: 전략 전환 → 클러스터 안 k6 Job → 결과 수집 → DB 정합성 확인
#
#   ./load-test/lock-compare.sh <hot|spread> <NONE|OPTIMISTIC|PESSIMISTIC|ATOMIC_UPDATE|REDIS|REDIS_SPIN> [RATE] [DURATION]
#   예) ./load-test/lock-compare.sh hot PESSIMISTIC          # 50건/초 × 60초
#       ./load-test/lock-compare.sh spread REDIS 200 60s
#
# 왜 클러스터 안에서 도나 (2026-11-22 결정): 맥북 Wi-Fi·Tailscale 구간의 흔들림이 지연에 섞이지 않게,
# 그리고 core는 Ingress가 없어 밖에서 닿지 않는다 (ADR-0002). k6-operator는 12주 차 → 지금은 일회성 Job
#
# 결과: load-test/results/lock-<시나리오>-<전략>-<RUN>.txt (k6 요약 + 지표 차이 + 정합성). results/는 Git 밖
set -uo pipefail
cd "$(dirname "$0")"
source ../infra/scripts/lab.env   # KUBECONFIG, PATH

SCENARIO="${1:?hot | spread}"
STRATEGY="${2:?전략 이름}"
RATE="${3:-$([ "$SCENARIO" = hot ] && echo 50 || echo 200)}"
DURATION="${4:-60s}"
NS=bank
RUN="$(date +%m%d%H%M%S)"
JOB="k6-lock"
K6_IMAGE="grafana/k6:2.3.0"   # 맥북 k6와 같은 버전
OUT="results/lock-${SCENARIO}-${STRATEGY}-${RUN}.txt"
MGMT_PORT=18081
mkdir -p results

log() { echo "[$(date +%H:%M:%S)] $*"; }

# 관리 포트(8081)는 Service에 없다 → 파드로 직접 port-forward (ExperimentsEndpoint 주석)
kubectl -n "$NS" port-forward deploy/core "${MGMT_PORT}:8081" >/dev/null 2>&1 &
PF=$!
trap 'kill $PF 2>/dev/null || true' EXIT
for _ in $(seq 30); do curl -sf "localhost:${MGMT_PORT}/actuator/experiments" >/dev/null && break; sleep 0.5; done

# 1) 전략 전환
changed=$(curl -sf -X POST "localhost:${MGMT_PORT}/actuator/experiments" -H 'Content-Type: application/json' \
  -d "{\"lockStrategy\":\"${STRATEGY}\"}") || { log "❌ 전략 전환 실패 (이름 확인: $STRATEGY)"; exit 1; }
log "전략: $changed"

# 2) 전 지표: 낙관적 락 재시도 수, Redis가 처리한 명령 수
retries() { curl -sf "localhost:${MGMT_PORT}/actuator/prometheus" | awk '/^core_posting_optimistic_retries_total/ {s+=$2} END {print s+0}'; }
redis_cmds() { kubectl -n "$NS" exec redis-0 -- redis-cli INFO stats | awk -F: '/^total_commands_processed/ {print $2+0}'; }
# 명령 종류별 호출 수 (cmdstat_<명령>:calls=N,...) → 전후 차이로 "락 한 번에 무슨 명령이 몇 개" 를 본다
redis_cmdstats() { kubectl -n "$NS" exec redis-0 -- redis-cli INFO commandstats | tr -d '\r' | awk -F'[_:=,]' '/^cmdstat_/ {print $2, $4}'; }
r0=$(retries); c0=$(redis_cmds); s0=$(redis_cmdstats)

# 3) k6 Job: 스크립트는 ConfigMap으로. core와 다른 노드에 띄워 k6가 core의 CPU를 뺏지 않게
kubectl -n "$NS" create configmap k6-lock-script --from-file=lock-compare.js -o yaml --dry-run=client | kubectl apply -f - >/dev/null
kubectl -n "$NS" delete job "$JOB" --ignore-not-found --wait=true >/dev/null
cat <<EOF | kubectl apply -f - >/dev/null
apiVersion: batch/v1
kind: Job
metadata:
  name: ${JOB}
  namespace: ${NS}
  labels: { app.kubernetes.io/part-of: bank-msa-lab, experiment: week-07-locks }
spec:
  backoffLimit: 0
  ttlSecondsAfterFinished: 3600
  template:
    metadata:
      labels: { experiment: week-07-locks }
    spec:
      restartPolicy: Never
      affinity:
        podAntiAffinity:
          requiredDuringSchedulingIgnoredDuringExecution:
            - topologyKey: kubernetes.io/hostname
              labelSelector: { matchLabels: { app.kubernetes.io/name: core } }
      containers:
        - name: k6
          image: ${K6_IMAGE}
          args: [run, --quiet, /scripts/lock-compare.js]
          env:
            - { name: SCENARIO, value: "${SCENARIO}" }
            - { name: RATE, value: "${RATE}" }
            - { name: DURATION, value: "${DURATION}" }
            - { name: RUN, value: "${RUN}" }
          resources:
            requests: { cpu: 500m, memory: 256Mi }
            limits: { memory: 1Gi }
          volumeMounts: [{ name: script, mountPath: /scripts }]
      volumes:
        - name: script
          configMap: { name: k6-lock-script }
EOF
log "k6 시작: $SCENARIO / $STRATEGY / ${RATE}건/초 × $DURATION (RUN=$RUN)"

# 4) 끝날 때까지 기다림 (성공이든 실패든)
for _ in $(seq 120); do
  st=$(kubectl -n "$NS" get job "$JOB" -o jsonpath='{.status.succeeded}{.status.failed}' 2>/dev/null)
  [ -n "$st" ] && break
  sleep 5
done
[ -z "$st" ] && log "⚠️ 10분 안에 안 끝남"
k6_ok=$([ "$(kubectl -n "$NS" get job "$JOB" -o jsonpath='{.status.succeeded}')" = 1 ] && echo 1 || echo 0)
kubectl -n "$NS" logs "job/$JOB" > "$OUT" 2>&1
log "k6 끝 → $OUT"

# 5) 후 지표
r1=$(retries); c1=$(redis_cmds); s1=$(redis_cmdstats)

# 6) 정합성: 이 회차 계좌만. 잔액(account) vs 원장 합(ledger, CUSTOMER_DEPOSIT는 CREDIT이 +), 그리고 전체 합
expected=$([ "$SCENARIO" = hot ] && echo 1000000000 || echo 200000000)
sql="
with a as (select id, balance from account.accounts where owner_id like 'lock-${RUN}-%'),
     l as (select account_id, sum(case when direction = 'CREDIT' then amount else -amount end) s
           from ledger.journal_line where account_id in (select id from a) group by account_id),
     e as (select count(distinct entry_id) n from ledger.journal_line
           where account_id in (select id from a) and entry_id in (select id from ledger.journal_entry where type = 'TRANSFER'))
select count(*), sum(a.balance), sum(coalesce(l.s, 0)), count(*) filter (where a.balance <> coalesce(l.s, 0)), (select n from e)
from a left join l on l.account_id = a.id;"
IFS='|' read -r n bal led mism entries < <(kubectl -n "$NS" exec core-db-1 -c postgres -- psql -U postgres -d core -At -c "$sql")

{
  echo
  echo "── 실행기 요약 ($(date '+%F %T')) ──"
  echo "시나리오=$SCENARIO 전략=$STRATEGY 속도=${RATE}/s 시간=$DURATION RUN=$RUN"
  echo "낙관적 락 재시도: $((r1 - r0))"
  echo "Redis 명령 수: $((c1 - c0))"
  echo "Redis 명령 종류별 (차이 > 0):"
  awk 'NR==FNR {before[$1]=$2; next} {d=$2-before[$1]; if (d>0) printf "    %-14s %d\n", $1, d}' <(echo "$s0") <(echo "$s1") | sort -k2 -nr
  echo "계좌 ${n}개 / 송금 전표 ${entries}건"
  echo "잔액 합 ${bal} / 원장 합 ${led} / 기대 ${expected}"
  echo "잔액≠원장 계좌: ${mism}개"
  [ "$k6_ok" = 1 ] || echo "⚠️ k6 Job 실패 (스크립트 오류·threshold): 위 로그 확인. 아래 숫자는 참고만"
  if [ "${entries:-0}" = 0 ]; then echo "정합성: ? (송금이 0건이라 판정 불가)"
  elif [ "$bal" = "$expected" ] && [ "$led" = "$expected" ] && [ "$mism" = 0 ]; then echo "정합성: ✅"; else echo "정합성: ❌ 깨짐"; fi
} | tee -a "$OUT"
