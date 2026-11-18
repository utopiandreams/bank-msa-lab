#!/bin/bash

# Required parameters:
# @raycast.schemaVersion 1
# @raycast.title Bank Lab Down
# @raycast.mode fullOutput
# @raycast.argument1 { "type": "dropdown", "placeholder": "대상", "data": [{"title": "전부 (동시에)", "value": "all"}, {"title": "proxmox (VM 4대, 컨트롤 플레인)", "value": "proxmox"}, {"title": "proxmox2", "value": "proxmox2"}] }

# Optional parameters:
# @raycast.icon 🔴
# @raycast.packageName bank-msa-lab
# @raycast.description 호스트별 또는 전부 동시에 끄기: bank VM 정상 종료 → 호스트 poweroff
# @raycast.needsConfirmation true

exec "$(cd "$(dirname "$0")/.." && pwd)/cluster-down.sh" "${1:-all}"
