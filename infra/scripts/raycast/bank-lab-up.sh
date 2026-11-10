#!/bin/bash

# Required parameters:
# @raycast.schemaVersion 1
# @raycast.title Bank Lab Up
# @raycast.mode fullOutput

# Optional parameters:
# @raycast.icon 🟢
# @raycast.packageName bank-msa-lab
# @raycast.description Proxmox(Wake-on-LAN) → VM 4대 → k8s 노드 Ready까지 켜기

exec "$(cd "$(dirname "$0")/.." && pwd)/cluster-up.sh"
