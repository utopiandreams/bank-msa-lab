#!/bin/bash
# 평소 트래픽 반복 실행기 (2026-11-26 운영 모드). 보통은 직접 부르지 않고 steadyctl.sh(= Raycast "Bank Traffic")가
# ~/.bank-lab/steady/run/ 에 복사해 launchd(맥 서비스 관리자)로 돌린다: 터미널을 닫아도, 맥을 재시작해도 계속 돈다.
#
# 왜 클러스터 밖(맥북)인가 (개발자 결정): 부하를 주는 쪽이 검사받는 시스템 안에 있으면 노드가 죽을 때 같이 죽어서
# "시스템이 죽어 송금이 줄었다"와 "부하를 주던 쪽이 죽었다"를 구분할 수 없다 (pitfalls 43). 그리고 고객과 같은 길로 들어온다.
# 대가: 맥북 Wi-Fi 흔들림이 k6 쪽 지연에 섞인다 → 지연은 서버 지표(Grafana)로 본다. k6 요약은 "몇 건 보냈고 결과가 뭐였나" 기록용
#
# 왜 복사해서 돌리나: 저장소가 ~/Desktop 아래라 macOS 개인정보 보호(TCC)가 launchd 작업의 접근을 막는다
# 단계: ~/.bank-lab/steady/level 파일 ("<normal|1|2|3> <바꾼 시각>"). 1~3단계는 정한 시간이 지나면 평상시로 되돌린다
# 기록: ~/.bank-lab/steady/logs/<시각>-<단계>.txt (실행마다 k6 요약, 7일 보관), 실행기 로그는 runner.log
# 맥이 잠들면 멈춘다: caffeinate로 유휴 잠자기는 막지만, 덮개를 닫으면 전원 + 외부 모니터 없이는 잠든다
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
if [ -f "$HERE/lab.env" ]; then source "$HERE/lab.env"; else source "$HERE/../../infra/scripts/lab.env"; fi
ACCOUNTS="$HERE/steady-accounts.json"; [ -f "$ACCOUNTS" ] || ACCOUNTS="$HERE/../data/steady-accounts.json"

STATE="$HOME/.bank-lab/steady"
LEVEL_FILE="$STATE/level"
LOG_DIR="$STATE/logs"
mkdir -p "$LOG_DIR"
[ -f "$ACCOUNTS" ] || { echo "❌ 계좌 없음: ./load-test/steady/seed.sh 먼저"; exit 1; }
[ -f "$LEVEL_FILE" ] || echo "normal $(date +%s)" > "$LEVEL_FILE"

log() { echo "[$(date '+%m-%d %H:%M:%S')] $*"; }
minutes_of() { case "$1" in 1) echo 60 ;; 2) echo 30 ;; 3) echo 10 ;; *) echo 0 ;; esac; }   # 단계별 유지 시간 (steady.js 머리 주석)

# 모든 요청이 고객 입구(gateway Ingress)로만 들어간다. 입금도 POST /deposits (2026-11-26 개방) → kubectl·port-forward 필요 없음
K6=""
cleanup() {
  [ -n "$K6" ] && kill "$K6" 2>/dev/null
  log "멈춤"; exit 0
}
trap cleanup INT TERM

caffeinate -i -w $$ &   # 이 실행기가 도는 동안 맥이 유휴 잠자기에 들지 않게

log "시작"
while true; do
  read -r level since < "$LEVEL_FILE" || { level=normal; since=0; }
  now=$(date +%s)
  limit=$(( $(minutes_of "$level") * 60 ))
  run=600
  if [ "$limit" -gt 0 ]; then
    left=$(( since + limit - now ))
    if [ "$left" -le 0 ]; then
      echo "normal $now" > "$LEVEL_FILE"; log "${level}단계 시간 끝 → 평상시"; continue
    fi
    [ "$left" -lt "$run" ] && run=$left
  else
    to_hour=$(( 3600 - now % 3600 ))   # KST는 UTC+9(정시 차이)라 UTC 정각 = KST 정각
    [ "$to_hour" -lt "$run" ] && run=$to_hour
  fi
  [ "$run" -lt 20 ] && run=20

  out="$LOG_DIR/$(date +%Y%m%d-%H%M%S)-$level.txt"
  log "단계 $level, ${run}초 → $(basename "$out")"
  started=$(date +%s)
  # 백그라운드 + wait: 멈춤 신호가 오면 k6 실행이 끝날 때까지 기다리지 않고 바로 정리한다
  LEVEL="$level" RUN_SECONDS="$run" ACCOUNTS_FILE="$ACCOUNTS" k6 run --quiet "$HERE/steady.js" > "$out" 2>&1 &
  K6=$!
  wait "$K6"; code=$?
  K6=""
  grep -E "steady_outcome\{|http_req_failed|dropped_iterations" "$out" | sed 's/^/    /'

  # 단계를 바꾸면 steadyctl이 k6를 끊는다 → 바로 다음 실행. 그 밖에 금방 실패했으면 잠깐 쉰다 (클러스터 꺼짐 등)
  read -r now_level _ < "$LEVEL_FILE"
  if [ "$code" -ne 0 ] && [ "$now_level" = "$level" ] && [ $(( $(date +%s) - started )) -lt 15 ]; then
    log "⚠️ k6 바로 실패 (exit $code, $out). 10초 뒤 다시"; sleep 10
  fi
  find "$LOG_DIR" -name '*.txt' -mtime +7 -delete 2>/dev/null
done
