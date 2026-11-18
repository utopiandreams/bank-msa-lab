#!/usr/bin/env bash
# 실험 클러스터 끄기 (호스트 단위, 병렬)
#   cluster-down.sh            → 전부 (proxmox, proxmox2)
#   cluster-down.sh proxmox2   → 하나만
#
# 호스트마다: bank-* VM 정상 종료(워커들 동시에 → 컨트롤 플레인) → 호스트 poweroff.
# 호스트끼리는 동시에 진행한다. 전원 버튼 대신 이걸 쓴다 (디스크 손상 방지).
#
# 주의 (노드 확장·HA 뒤): 지금은 컨트롤 플레인이 proxmox 한 대에만 있어서 호스트 사이 순서가 없다.
# 서버 3대(내장 etcd)가 되면 "하나만 끄기"는 과반수가 남는지 먼저 따져야 한다 (3대 중 2대를 끄면 쓰기 멈춤).
set -uo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lab.env"

targets=$(resolve_targets "$@") || exit 2

host_down() {
  host_info "$1"
  if ! host_ssh true 2>/dev/null; then
    log "이미 꺼져 있음 (또는 네트워크 없음)"
    return 0
  fi

  # 실행 중인 bank-* VM. 컨트롤 플레인(CP_VMID)은 마지막에 끈다:
  # 먼저 끄면 남은 워커들이 종료 중에 API 서버를 못 찾아 kubelet이 재시도하느라 종료가 늘어진다
  local running workers="" cp=""
  running=$(host_ssh "qm list" 2>/dev/null | awk '$2 ~ /^bank-/ && $3 == "running" {print $1}')
  for id in $running; do
    if [ "$id" = "$CP_VMID" ]; then cp=$id; else workers="$workers $id"; fi
  done

  # 각 VM에 ACPI 종료 신호 → OS가 정상 종료, 2분 넘으면 강제 종료
  if [ -n "${workers// /}" ]; then
    log "VM 종료 중:$workers"
    host_ssh "for id in $workers; do (qm shutdown \$id --timeout 120 || qm stop \$id) >/dev/null 2>&1 & done; wait"
  fi
  if [ -n "$cp" ]; then
    log "컨트롤 플레인 VM 종료 중: $cp"
    host_ssh "qm shutdown $cp --timeout 120 >/dev/null 2>&1 || qm stop $cp >/dev/null 2>&1"
  fi

  log "호스트 전원 끄기"
  host_ssh "poweroff" >/dev/null 2>&1 || true
  log "✅ 종료 명령 완료"
}

pids=""
for t in $targets; do
  host_down "$t" &
  pids="$pids $!"
done
fail=0
for pid in $pids; do wait "$pid" || fail=1; done

echo
echo "[$(ts)] 완료 ($targets). 다시 켤 때는 cluster-up.sh"
exit $fail
