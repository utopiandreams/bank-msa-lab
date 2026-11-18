#!/bin/bash

# Required parameters:
# @raycast.schemaVersion 1
# @raycast.title Bank Grafana
# @raycast.mode silent

# Optional parameters:
# @raycast.icon 📈
# @raycast.packageName bank-msa-lab
# @raycast.description Grafana를 Chrome으로 열고 admin 비밀번호를 클립보드에 복사

source "$(cd "$(dirname "$0")/.." && pwd)/lab.env"
open -a "Google Chrome" "$GRAFANA_URL"
# 비밀번호는 Git이 아니라 클러스터 Secret에 있다 (docs/infra/README.md "관측성 기본 스택"). 로그인 화면에서 붙여넣기만
if pw=$(kubectl -n monitoring get secret grafana-admin -o jsonpath='{.data.admin-password}' --request-timeout=3s 2>/dev/null | base64 -d) && [[ -n "$pw" ]]; then
  printf '%s' "$pw" | pbcopy
  echo "Grafana 열기 (admin 비밀번호 복사됨)"
else
  echo "Grafana 열기 (클러스터 응답 없음: 비밀번호 복사 못 함)"
fi
