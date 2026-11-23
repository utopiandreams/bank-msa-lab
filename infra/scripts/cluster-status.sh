#!/usr/bin/env bash
# 실험 클러스터 상태 한눈에 보기: 물리 장비 3대 / VM / k8s 노드 / Argo CD 앱
set -uo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lab.env"

echo "── 물리 장비 ─────────────────────────────"
# 모든 확인은 노트북(bank-cp3)을 거친다 (2026-11-23). 맥북이 집 안이든 Tailscale로 밖이든 같은 경로.
# 노트북부터 보는 이유: 노트북에 못 붙으면 "호스트가 꺼졌다"와 "맥북이 집에 못 닿는다"를 구분할 수 없다

# 1) 중재자 노트북: 항상 켜져 있어야 한다. 전원선이 빠지면 배터리로 버티는 중이므로 눈에 띄게 표시
if out=$(witness 'uptime -p; cat /sys/class/power_supply/ADP*/online; cat /sys/class/power_supply/BAT*/capacity; free -m | awk "/Mem:/ {print \$3\"/\"\$2}"' 2>/dev/null); then
  up=$(sed -n 1p <<<"$out"); ac=$(sed -n 2p <<<"$out"); bat=$(sed -n 3p <<<"$out"); mem=$(sed -n 4p <<<"$out")
  if [[ "$ac" == "1" ]]; then power="🔌 전원 연결, 배터리 ${bat}%"; else power="⚠️ 배터리로 동작 중 ${bat}% (전원선 확인)"; fi
  echo "🟢 bank-cp3  ${WITNESS_IP}  ${up}"
  echo "     ${power}, 메모리 ${mem}MB"
else
  echo "🔴 bank-cp3  ${WITNESS_IP}  응답 없음"
  echo "     → 집 밖이면 맥북 Tailscale 연결부터 확인 (메뉴바 아이콘). 집 안이면 노트북 전원·랜선"
  echo "     → 노트북을 거쳐 보는 구조라 나머지 장비 상태는 알 수 없음"
  exit 1
fi

# 2) Proxmox 호스트: 노트북에서 ping으로 켜짐 판단 → 켜졌으면 노트북을 거쳐(ProxyJump) VM 목록
for name in $LAB_HOSTS; do
  host_info "$name"
  if witness "ping -c1 -W2 $H_IP" >/dev/null 2>&1; then
    if out=$(via_witness "$H_SSH" 'uptime -p; qm list' 2>/dev/null); then
      printf "🟢 %-9s %s  %s\n" "$name" "$H_IP" "$(echo "$out" | head -1)"
      echo "$out" | awk '/bank-/ {printf "     %-6s %-10s %s\n", $1, $2, $3}'
    else
      printf "🟡 %-9s %s  ping은 되는데 SSH 안 됨 (부팅 중일 수 있음)\n" "$name" "$H_IP"
    fi
  else
    printf "🔴 %-9s %s  꺼짐\n" "$name" "$H_IP"
  fi
done

echo
echo "── k8s ───────────────────────────────────"
# 컨트롤 플레인 VM이 ping도 안 되면 kubectl을 부르지 않는다 (kubectl은 타임아웃을 여러 번 재시도해 20초 넘게 걸린다)
if ! witness "ping -c1 -W2 $CP_IP" >/dev/null 2>&1; then
  echo "  컨트롤 플레인 VM(${CP_IP}) 꺼짐"
elif kubectl get nodes --request-timeout=3s >/dev/null 2>&1; then
  kubectl get nodes --no-headers | awk '{printf "  %-8s %s\n", $1, $2}'
  echo
  kubectl top nodes --no-headers 2>/dev/null | awk '{printf "  %-8s CPU %-5s 메모리 %s (%s)\n", $1, $3, $4, $5}'
  echo
  kubectl -n argocd get applications --no-headers 2>/dev/null | awk '{printf "  %-22s %-10s %s\n", $1, $2, $3}'
else
  echo "  kube-apiserver 응답 없음 (VM이 꺼져 있거나 부팅 중)"
fi
