#!/usr/bin/env bash
# Argo CD를 최초 1회 Helm으로 설치하고, App of Apps의 뿌리(root)를 등록한다.
# 이후의 모든 설치/변경은 Git(deploy/argocd/apps/) → Argo CD가 처리한다.
#
# 사용: KUBECONFIG=~/.kube/bank-msa-lab.yaml ./infra/bootstrap/install-argocd.sh
set -euo pipefail

ARGOCD_CHART_VERSION="10.17.1"   # Argo CD v3.6.1
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

helm repo add argo https://argoproj.github.io/argo-helm >/dev/null 2>&1 || true
helm repo update argo >/dev/null

helm upgrade --install argocd argo/argo-cd \
  --namespace argocd --create-namespace \
  --version "${ARGOCD_CHART_VERSION}" \
  --values "${ROOT}/deploy/argocd/bootstrap/values.yaml" \
  --wait --timeout 10m

kubectl apply -f "${ROOT}/deploy/argocd/root.yaml"

echo
echo "Argo CD 설치 완료, root 앱 등록 완료"
echo "UI      : http://argocd.172.30.1.240.nip.io  (MetalLB가 동기화된 뒤 접속 가능)"
echo "계정    : admin"
echo "비밀번호: kubectl -n argocd get secret argocd-initial-admin-secret -o jsonpath='{.data.password}' | base64 -d; echo"
