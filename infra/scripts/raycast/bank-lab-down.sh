#!/bin/bash

# Required parameters:
# @raycast.schemaVersion 1
# @raycast.title Bank Lab Down
# @raycast.mode fullOutput

# Optional parameters:
# @raycast.icon 🔴
# @raycast.packageName bank-msa-lab
# @raycast.description VM 4대 정상 종료 후 Proxmox 호스트 전원 끄기
# @raycast.needsConfirmation true

exec "$(cd "$(dirname "$0")/.." && pwd)/cluster-down.sh"
