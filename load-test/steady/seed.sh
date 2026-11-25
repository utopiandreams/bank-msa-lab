#!/usr/bin/env bash
# 평소 트래픽용 계좌 준비 (한 번만): 계좌 N개 × BALANCE원 → ConfigMap steady-accounts (bank 네임스페이스)
#
#   ./load-test/steady/seed.sh [N=200] [BALANCE=10000000]
#
# 계좌 만드는 방법은 기준선과 같다 (seed-accounts.sh: 개설은 고객 경로, 입금은 관리자 통로).
# 사용자 이름이 steady-로 시작해 실험용 계좌(k6-…)와 구분된다
set -euo pipefail
cd "$(dirname "$0")/.."
source ../infra/scripts/lab.env

N="${1:-200}"
BALANCE="${2:-10000000}"
OUT="data/steady-accounts.json"

PREFIX=steady OUT="$OUT" ./seed-accounts.sh "$N" "$BALANCE"
kubectl -n bank create configmap steady-accounts --from-file=accounts.json="$OUT" -o yaml --dry-run=client | kubectl apply -f -
echo "✅ ConfigMap steady-accounts (계좌 $N개). 트래픽 파드가 이미 떠 있으면: kubectl -n bank rollout restart deploy/steady-traffic"
