#!/usr/bin/env bash
# 실험 클러스터 상태 한눈에 보기: Proxmox 호스트 / VM / k8s 노드 / Argo CD 앱
set -uo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lab.env"

if ! pve_up; then
  echo "🔴 Proxmox 꺼짐 (${PVE_IP})"
  exit 0
fi
echo "🟢 Proxmox 켜짐 (${PVE_IP})"
echo
pve "qm list" 2>/dev/null | awk 'NR==1 || /bank-/' | awk '{printf "  %-6s %-10s %-8s\n", $1, $2, $3}'
echo
if kubectl get nodes --request-timeout=3s >/dev/null 2>&1; then
  kubectl get nodes --no-headers | awk '{printf "  %-8s %s\n", $1, $2}'
  echo
  kubectl -n argocd get applications --no-headers 2>/dev/null | awk '{printf "  %-15s %-10s %s\n", $1, $2, $3}'
else
  echo "  k8s: kube-apiserver 응답 없음 (VM이 꺼져 있거나 부팅 중)"
fi
