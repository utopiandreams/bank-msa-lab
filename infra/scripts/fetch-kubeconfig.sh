#!/usr/bin/env bash
# k3s 컨트롤 플레인에서 kubeconfig를 가져와 맥북에서 kubectl을 쓸 수 있게 한다.
# 저장 위치는 레포 밖(~/.kube/bank-msa-lab.yaml). kubeconfig는 클러스터 관리자 권한이라 커밋 금지.
set -euo pipefail

CP_IP="${CP_IP:-172.30.1.230}"
VM_USER="${VM_USER:-ubuntu}"
OUT="${HOME}/.kube/bank-msa-lab.yaml"

mkdir -p "${HOME}/.kube"
umask 077

ssh -o BatchMode=yes -o StrictHostKeyChecking=accept-new "${VM_USER}@${CP_IP}" \
  'sudo cat /etc/rancher/k3s/k3s.yaml' \
  | sed -e "s/127.0.0.1/${CP_IP}/" -e "s/: default$/: bank-msa-lab/" \
  > "$OUT"

echo "저장: $OUT"
echo "사용: export KUBECONFIG=$OUT && kubectl get nodes -o wide"
