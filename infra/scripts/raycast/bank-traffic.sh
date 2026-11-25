#!/bin/bash

# Required parameters:
# @raycast.schemaVersion 1
# @raycast.title Bank Traffic
# @raycast.mode compact
# @raycast.argument1 { "type": "dropdown", "placeholder": "동작", "data": [{"title": "켜기 · 평상시 (시간대별 0.2~1.5건/초)", "value": "normal"}, {"title": "1단계 바쁜 날 (30건/초, 60분)", "value": "1"}, {"title": "2단계 꺾이기 직전 (150건/초, 30분)", "value": "2"}, {"title": "3단계 붕괴 (800건/초, 10분, 파드가 죽을 수 있음)", "value": "3"}, {"title": "상태", "value": "status"}, {"title": "끄기", "value": "stop"}] }

# Optional parameters:
# @raycast.icon 🚦
# @raycast.packageName bank-msa-lab
# @raycast.description 맥북에서 은행에 송금 트래픽 흘리기: 켜기·끄기·단계 (load-test/steady/steadyctl.sh). 1~3단계는 정한 시간 뒤 평상시로

exec "$(cd "$(dirname "$0")/../../.." && pwd)/load-test/steady/steadyctl.sh" "${1:-status}"
