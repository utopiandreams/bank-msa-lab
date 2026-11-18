#!/bin/bash

# Required parameters:
# @raycast.schemaVersion 1
# @raycast.title Bank Lab Up
# @raycast.mode fullOutput
# @raycast.argument1 { "type": "dropdown", "placeholder": "대상", "data": [{"title": "전부 (동시에)", "value": "all"}, {"title": "proxmox (VM 4대, 컨트롤 플레인)", "value": "proxmox"}, {"title": "proxmox2", "value": "proxmox2"}] }

# Optional parameters:
# @raycast.icon 🟢
# @raycast.packageName bank-msa-lab
# @raycast.description 호스트별 또는 전부 동시에 켜기: Wake-on-LAN → bank VM 시작 → k8s 노드 Ready

exec "$(cd "$(dirname "$0")/.." && pwd)/cluster-up.sh" "${1:-all}"
