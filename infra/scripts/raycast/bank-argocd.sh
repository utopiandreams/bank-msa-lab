#!/bin/bash

# Required parameters:
# @raycast.schemaVersion 1
# @raycast.title Bank Argo CD
# @raycast.mode silent

# Optional parameters:
# @raycast.icon 🐙
# @raycast.packageName bank-msa-lab
# @raycast.description Argo CD 화면을 Chrome으로 열기

source "$(cd "$(dirname "$0")/.." && pwd)/lab.env"
open -a "Google Chrome" "$ARGOCD_URL"
echo "Argo CD 열기"
