#!/bin/bash
# 평소 트래픽 조종 (2026-11-26). Raycast "Bank Traffic"이 이걸 부른다 (infra/scripts/raycast/bank-traffic.sh)
#
#   steadyctl.sh normal        켜기 / 평상시로 (시간대별 0.2~1.5건/초)
#   steadyctl.sh 1 | 2 | 3     단계 올리기: 30 / 150 / 800건/초, 60 / 30 / 10분 뒤 자동으로 평상시 (steady.js 머리 주석)
#   steadyctl.sh stop          끄기
#   steadyctl.sh status        상태 한 줄
#
# 켜면 launchd(맥 서비스 관리자)에 등록: 터미널을 닫아도, 맥을 재시작해도 계속 돈다. 끄면 등록을 지운다.
# 실행 파일은 ~/.bank-lab/steady/run/ 에 복사해 쓴다 (저장소가 ~/Desktop 아래라 launchd 작업은 TCC에 막힘). 켤 때마다 다시 복사
set -u
SRC="$(cd "$(dirname "$0")" && pwd)"
STATE="$HOME/.bank-lab/steady"
RUN="$STATE/run"
LABEL="lab.bank.steady-traffic"
PLIST="$HOME/Library/LaunchAgents/$LABEL.plist"
DOMAIN="gui/$(id -u)"

running() { launchctl print "$DOMAIN/$LABEL" >/dev/null 2>&1; }
set_level() { mkdir -p "$STATE"; echo "$1 $(date +%s)" > "$STATE/level"; }
kick() { pkill -f "k6 run --quiet $RUN/steady.js" 2>/dev/null; return 0; }   # 지금 실행을 끊으면 실행기가 새 단계로 바로 다시 돈다
describe() {
  case "$1" in
    normal) echo "평상시 (시간대별 0.2~1.5건/초)" ;;
    1) echo "1단계 바쁜 날 (30건/초)" ;;
    2) echo "2단계 꺾이기 직전 (150건/초)" ;;
    3) echo "3단계 붕괴 (800건/초, 파드가 죽을 수 있음)" ;;
  esac
}
minutes_of() { case "$1" in 1) echo 60 ;; 2) echo 30 ;; 3) echo 10 ;; *) echo 0 ;; esac; }

start() {
  [ -f "$SRC/../data/steady-accounts.json" ] || { echo "❌ 계좌 없음: ./load-test/steady/seed.sh 먼저"; exit 1; }
  # 터미널에서 직접 돌리는 실행기가 있으면 부하가 두 배가 된다
  if pgrep -f "$SRC/run-local.sh" >/dev/null; then echo "❌ 터미널에서 run-local.sh가 이미 돌고 있음 (그걸 먼저 Ctrl+C)"; exit 1; fi
  mkdir -p "$RUN" "$STATE/logs" "$(dirname "$PLIST")"
  cp "$SRC/run-local.sh" "$SRC/steady.js" "$SRC/../data/steady-accounts.json" "$SRC/../../infra/scripts/lab.env" "$RUN/"
  cat > "$PLIST" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key><string>$LABEL</string>
  <key>ProgramArguments</key><array><string>/bin/bash</string><string>$RUN/run-local.sh</string></array>
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key><true/>
  <key>StandardOutPath</key><string>$STATE/runner.log</string>
  <key>StandardErrorPath</key><string>$STATE/runner.log</string>
</dict>
</plist>
EOF
  launchctl bootstrap "$DOMAIN" "$PLIST"
}

status() {
  if ! running; then echo "⚪ 꺼짐"; return; fi
  read -r level since < "$STATE/level" 2>/dev/null || level=normal
  limit=$(( $(minutes_of "$level") * 60 ))
  if [ "$limit" -gt 0 ]; then
    left=$(( (since + limit - $(date +%s)) / 60 ))
    echo "🟢 켜짐 · $(describe "$level") · 약 ${left}분 뒤 평상시"
  else
    echo "🟢 켜짐 · $(describe "$level")"
  fi
}

case "${1:-status}" in
  normal|start)
    set_level normal
    if running; then kick; else start; fi
    status ;;
  1|2|3)
    set_level "$1"
    if running; then kick; else start; fi
    status ;;
  stop)
    running && launchctl bootout "$DOMAIN/$LABEL"
    rm -f "$PLIST"
    pkill -f "k6 run --quiet $RUN/steady.js" 2>/dev/null
    echo "⚪ 꺼짐" ;;
  status) status ;;
  *) echo "사용법: steadyctl.sh normal | 1 | 2 | 3 | stop | status"; exit 1 ;;
esac
