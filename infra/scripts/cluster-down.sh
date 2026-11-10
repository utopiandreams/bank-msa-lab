#!/usr/bin/env bash
# 실험 클러스터 끄기: VM 4대 정상 종료(워커 → 컨트롤 플레인) → Proxmox 호스트 전원 끄기
# 전원 버튼을 누르는 대신 이걸 쓴다 (디스크 손상 방지).
set -uo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lab.env"

if ! pve_up; then
  echo "[$(ts)] Proxmox가 이미 꺼져 있음"
  exit 0
fi

# 1) 워커 먼저 동시에 종료 (각 VM에 ACPI 종료 신호 → OS가 정상 종료, 2분 넘으면 강제 종료)
echo "[$(ts)] 워커 VM 종료 중: ${WORKER_VMIDS[*]}"
pve "for id in ${WORKER_VMIDS[*]}; do (qm shutdown \$id --timeout 120 || qm stop \$id) >/dev/null 2>&1 & done; wait"

# 2) 컨트롤 플레인 종료
echo "[$(ts)] 컨트롤 플레인 VM 종료 중: ${CP_VMID}"
pve "qm shutdown ${CP_VMID} --timeout 120 >/dev/null 2>&1 || qm stop ${CP_VMID} >/dev/null 2>&1"

pve "qm list" 2>/dev/null | awk 'NR==1 || /bank-/'

# 3) 호스트 전원 끄기
echo "[$(ts)] Proxmox 호스트 전원 끄기"
pve "poweroff" >/dev/null 2>&1 || true
echo "[$(ts)] ✅ 종료 명령 완료. 다시 켤 때는 cluster-up.sh"
