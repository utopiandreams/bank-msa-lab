#!/usr/bin/env bash
# 평소 트래픽용 계좌 준비 (한 번만): 계좌 N개 × BALANCE원 → load-test/data/steady-accounts.json
#
#   ./load-test/steady/seed.sh [N=200] [BALANCE=10000000]
#
# 계좌 만드는 방법은 기준선과 같다 (seed-accounts.sh: 개설은 고객 경로, 입금은 관리자 통로).
# 사용자 이름이 steady-로 시작해 실험용 계좌(k6-…)와 구분된다
set -euo pipefail
cd "$(dirname "$0")/.."

PREFIX=steady OUT="data/steady-accounts.json" ./seed-accounts.sh "${1:-200}" "${2:-10000000}"
