#!/usr/bin/env bash
# k6 기준선용 테스트 계좌 준비: 계좌 N개를 만들고 각각 BALANCE원을 넣는다.
#
#   ./load-test/seed-accounts.sh [N=200] [BALANCE=1000000]
#   → load-test/data/accounts.json  ([{ "userId": "...", "accountId": "..." }, ...])
#
# 두 길을 일부러 다르게 쓴다:
#   - 계좌 개설: 고객 경로 그대로 (gateway → core, 토큰의 sub가 예금주)
#   - 입금:      관리자 통로 (kubectl port-forward로 맥북 → core 파드 직접)
#     입금 API는 고객 입구(gateway)에 일부러 열지 않았다 (ADR-0002). 열면 누구나 자기 계좌에 돈을 찍어 낸다.
#     port-forward는 kubeconfig(클러스터 관리자 권한)가 있어야 쓸 수 있어서, 실무의 백오피스 도구 자리에 해당한다.
#     실제 은행에서 돈이 들어오는 길(타행 입금)은 5주 차 bank-mock 이후에 생긴다.
set -euo pipefail
cd "$(dirname "$0")"
source ../infra/scripts/lab.env   # KUBECONFIG, PATH

N="${1:-200}"
BALANCE="${2:-1000000}"
GATEWAY="${GATEWAY_URL:-http://gateway.172.30.1.240.nip.io}"
CORE_LOCAL_PORT=18080
RUN="$(date +%m%d%H%M)"          # 다시 돌려도 사용자 이름이 겹치지 않게
OUT="data/accounts.json"
mkdir -p data

# 1) 관리자 통로 열기 (스크립트가 끝나면 닫는다)
kubectl -n bank port-forward svc/core "${CORE_LOCAL_PORT}:80" >/dev/null 2>&1 &
PF_PID=$!
trap 'kill $PF_PID 2>/dev/null || true' EXIT
for _ in $(seq 30); do curl -sf "http://localhost:${CORE_LOCAL_PORT}/livez" >/dev/null && break; sleep 0.5; done
curl -sf "http://localhost:${CORE_LOCAL_PORT}/livez" >/dev/null || { echo "❌ core port-forward 실패"; exit 1; }

echo "[$(date +%H:%M:%S)] 계좌 ${N}개 × ${BALANCE}원 준비 (사용자 k6-${RUN}-NNN)"
echo "[" > "$OUT"
for i in $(seq 1 "$N"); do
  user="k6-${RUN}-$(printf %03d "$i")"
  # 2) 고객 경로: 토큰 → 계좌 개설
  token=$(curl -sf -X POST "$GATEWAY/auth/token" -H 'Content-Type: application/json' -d "{\"userId\":\"$user\"}" | jq -r .access_token)
  acc=$(curl -sf -X POST "$GATEWAY/accounts" -H "Authorization: Bearer $token" | jq -r .accountId)
  # 3) 관리자 통로: 입금. 멱등 키를 계좌 ID로 고정 → 스크립트를 중간에 다시 돌려도 이중 입금이 안 된다
  curl -sf -o /dev/null -X POST "http://localhost:${CORE_LOCAL_PORT}/postings/deposits" \
    -H "Idempotency-Key: seed-$acc" -H 'Content-Type: application/json' \
    -d "{\"accountId\":\"$acc\",\"amount\":$BALANCE}"
  sep=$([[ "$i" -lt "$N" ]] && echo "," || echo "")
  echo "  {\"userId\":\"$user\",\"accountId\":\"$acc\"}$sep" >> "$OUT"
  (( i % 50 == 0 )) && echo "[$(date +%H:%M:%S)]   ${i}/${N}"
done
echo "]" >> "$OUT"
echo "[$(date +%H:%M:%S)] ✅ ${OUT} (총 잔액 $(( N * BALANCE ))원)"
