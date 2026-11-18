#!/usr/bin/env bash
# 실험 클러스터 상태 한눈에 보기: 물리 장비 3대 / VM / k8s 노드 / Argo CD 앱
set -uo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lab.env"

echo "── 물리 장비 ─────────────────────────────"

# 1) 기존 Proxmox 호스트 (VM 4대)
if pve_up; then
  echo "🟢 proxmox   ${PVE_IP}  $(pve 'uptime -p' 2>/dev/null)"
  pve "qm list" 2>/dev/null | awk '/bank-/ {printf "     %-6s %-10s %s\n", $1, $2, $3}'
else
  echo "🔴 proxmox   ${PVE_IP}  꺼짐"
fi

# 2) 두 번째 Proxmox 호스트 (아직 VM 없음)
if out=$(pve2 'uptime -p; qm list' 2>/dev/null); then
  echo "🟢 proxmox2  ${PVE2_IP}  $(echo "$out" | head -1)"
  echo "$out" | awk 'NR>2 {printf "     %-6s %-10s %s\n", $1, $2, $3}'
else
  echo "🔴 proxmox2  ${PVE2_IP}  꺼짐 (또는 네트워크 없음)"
fi

# 3) 중재자 노트북: 항상 켜져 있어야 한다. 전원선이 빠지면 배터리로 버티는 중이므로 눈에 띄게 표시
if out=$(witness 'uptime -p; cat /sys/class/power_supply/ADP*/online; cat /sys/class/power_supply/BAT*/capacity; free -m | awk "/Mem:/ {print \$3\"/\"\$2}"' 2>/dev/null); then
  up=$(sed -n 1p <<<"$out"); ac=$(sed -n 2p <<<"$out"); bat=$(sed -n 3p <<<"$out"); mem=$(sed -n 4p <<<"$out")
  if [[ "$ac" == "1" ]]; then power="🔌 전원 연결, 배터리 ${bat}%"; else power="⚠️ 배터리로 동작 중 ${bat}% (전원선 확인)"; fi
  echo "🟢 bank-cp3  ${WITNESS_IP}  ${up}"
  echo "     ${power}, 메모리 ${mem}MB"
else
  echo "🔴 bank-cp3  ${WITNESS_IP}  응답 없음 (항상 켜져 있어야 하는 장비)"
fi

echo
echo "── k8s ───────────────────────────────────"
if kubectl get nodes --request-timeout=3s >/dev/null 2>&1; then
  kubectl get nodes --no-headers | awk '{printf "  %-8s %s\n", $1, $2}'
  echo
  kubectl top nodes --no-headers 2>/dev/null | awk '{printf "  %-8s CPU %-5s 메모리 %s (%s)\n", $1, $3, $4, $5}'
  echo
  kubectl -n argocd get applications --no-headers 2>/dev/null | awk '{printf "  %-22s %-10s %s\n", $1, $2, $3}'
else
  echo "  kube-apiserver 응답 없음 (VM이 꺼져 있거나 부팅 중)"
fi
