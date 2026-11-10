#!/bin/bash

# Required parameters:
# @raycast.schemaVersion 1
# @raycast.title Bank Lab Status
# @raycast.mode fullOutput

# Optional parameters:
# @raycast.icon 📊
# @raycast.packageName bank-msa-lab
# @raycast.description Proxmox / VM / k8s 노드 / Argo CD 앱 상태

exec "$(cd "$(dirname "$0")/.." && pwd)/cluster-status.sh"
