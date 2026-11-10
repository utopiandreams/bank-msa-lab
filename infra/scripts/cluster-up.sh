#!/usr/bin/env bash
# 실험 클러스터 켜기: Proxmox 호스트(Wake-on-LAN) → VM 4대 → k8s 노드 Ready 확인
# 맥북이 집 네트워크(172.30.1.0/24)에 있어야 Wake-on-LAN 패킷이 닿는다.
set -uo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lab.env"

# 1) Proxmox 호스트
if pve_up; then
  echo "[$(ts)] Proxmox 이미 켜져 있음"
else
  echo "[$(ts)] Proxmox 꺼져 있음 → Wake-on-LAN 매직 패킷 전송 (${PVE_MAC})"
  python3 - "$PVE_MAC" "$BROADCAST" <<'PY'
import socket, sys
mac = bytes.fromhex(sys.argv[1].replace(":", ""))
packet = b"\xff" * 6 + mac * 16          # 매직 패킷: FF x6 + MAC x16
s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
s.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
for addr in (sys.argv[2], "255.255.255.255"):
    for port in (9, 7):
        s.sendto(packet, (addr, port))
PY
  echo -n "[$(ts)] 부팅 대기"
  for _ in $(seq 60); do pve_up && break; echo -n "."; sleep 5; done; echo
  if ! pve_up; then
    echo "[$(ts)] ❌ 5분 안에 Proxmox가 응답하지 않음. BIOS의 Wake-on-LAN 설정이나 전원 상태를 확인하세요."
    exit 1
  fi
  echo "[$(ts)] Proxmox 응답함"
fi

# 2) VM 시작 (컨트롤 플레인 먼저)
for id in "$CP_VMID" "${WORKER_VMIDS[@]}"; do
  status=$(pve "qm status $id" 2>/dev/null | awk '{print $2}')
  if [[ "$status" == "running" ]]; then
    echo "[$(ts)] VM $id 이미 실행 중"
  else
    pve "qm start $id" >/dev/null 2>&1 && echo "[$(ts)] VM $id 시작" || echo "[$(ts)] ⚠️ VM $id 시작 실패"
  fi
done

# 3) k8s 노드 Ready 대기
total=$(( 1 + ${#WORKER_VMIDS[@]} ))
echo -n "[$(ts)] k8s 노드 Ready 대기"
for _ in $(seq 60); do
  ready=$(kubectl get nodes --no-headers 2>/dev/null | awk '$2=="Ready"' | wc -l | tr -d ' ')
  [[ "$ready" == "$total" ]] && break
  echo -n "."; sleep 5
done; echo

kubectl get nodes 2>/dev/null || { echo "[$(ts)] ❌ kube-apiserver에 접속 못 함"; exit 1; }
echo
echo "[$(ts)] ✅ 클러스터 준비 완료 (Ready ${ready}/${total})"
echo "  Argo CD : http://argocd.172.30.1.240.nip.io"
echo "  hello   : http://hello.172.30.1.240.nip.io"
