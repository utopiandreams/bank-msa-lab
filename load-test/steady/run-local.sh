#!/usr/bin/env bash
# 평소 트래픽을 맥북에서 끝없이 흘린다 (2026-11-26 운영 모드). 터미널(또는 tmux) 하나에 켜 두면 된다. 멈추기: Ctrl+C
#
#   ./load-test/steady/run-local.sh            # 계좌가 없으면 먼저 ./load-test/steady/seed.sh
#   RATE_SCALE=0.5 ./load-test/steady/run-local.sh   # 송금량 절반
#
# 왜 클러스터 밖(맥북)인가 (개발자 결정): 부하를 주는 쪽이 검사받는 시스템 안에 있으면 노드가 죽을 때 같이 죽어서
# "시스템이 죽어 송금이 줄었다"와 "부하를 주던 쪽이 죽었다"를 구분할 수 없다 (pitfalls 43). 그리고 고객과 같은 길로 들어온다.
# 대가: 맥북 Wi-Fi 흔들림이 k6 쪽 지연에 섞인다 → 지연은 서버 지표(Grafana)로 본다. k6 요약은 "몇 건 보냈고 결과가 뭐였나" 기록용
#
# 맥이 잠들면 멈춘다: caffeinate로 유휴 잠자기는 막지만, **덮개를 닫으면** 전원 + 외부 모니터 없이는 잠든다 (덮개는 열어 둔다)
# 결과: load-test/results/steady/<시작 시각>.txt (정각마다 하나, k6 요약). results/는 Git 밖
set -uo pipefail
cd "$(dirname "$0")"
source ../../infra/scripts/lab.env   # KUBECONFIG, PATH

CORE_PORT=18080
LOG_DIR=../results/steady
mkdir -p "$LOG_DIR"
[ -f ../data/steady-accounts.json ] || { echo "❌ 계좌 없음: ./load-test/steady/seed.sh 먼저"; exit 1; }

log() { echo "[$(date '+%m-%d %H:%M:%S')] $*"; }

# 관리자 통로(입금용 core port-forward). 파드가 바뀌거나 끊기면 kubectl이 끝나므로 다시 연다
( while true; do kubectl -n bank port-forward svc/core "${CORE_PORT}:80" >/dev/null 2>&1; sleep 2; done ) &
PF_LOOP=$!
cleanup() { kill "$PF_LOOP" 2>/dev/null; pkill -f "port-forward svc/core ${CORE_PORT}:80" 2>/dev/null; log "멈춤"; exit 0; }
trap cleanup INT TERM

caffeinate -i -w $$ &   # 이 스크립트가 도는 동안 맥이 유휴 잠자기에 들지 않게

log "평소 트래픽 시작 (RATE_SCALE=${RATE_SCALE:-1}), 기록: load-test/results/steady/"
while true; do
  out="$LOG_DIR/$(date +%Y%m%d-%H%M).txt"
  log "실행 → $(basename "$out")"
  if ! RATE_SCALE="${RATE_SCALE:-1}" k6 run --quiet steady.js > "$out" 2>&1; then
    log "⚠️ k6 비정상 종료 (내용: $out). 10초 뒤 다시"
    sleep 10
  fi
  grep -E "steady_outcome|http_req_failed|dropped_iterations" "$out" | sed 's/^/    /'
done
