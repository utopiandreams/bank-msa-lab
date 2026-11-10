#!/usr/bin/env bash
# Proxmox에 Terraform 전용 최소 권한 사용자/역할/토큰을 만들고,
# 토큰을 infra/terraform/terraform.tfvars에 바로 기록한다 (토큰을 화면에 출력하지 않음).
#
# 왜: 기존 root@pam!terraform 토큰은 privsep=0이라 root 권한 전체를 가졌다.
#     자동화 도구에는 필요한 권한만 준다 (최소 권한 원칙).
#
# 사용: ./infra/bootstrap/create-terraform-token.sh
# 재실행: 역할 권한은 갱신되고, 토큰은 기존 것을 지우고 새로 발급한다.
set -euo pipefail

PVE_HOST="${PVE_HOST:-root@172.30.1.200}"
PVE_USER="terraform@pve"
ROLE="TerraformProv"
TOKEN_NAME="provider"

# bpg/proxmox 프로바이더가 VM 생성·클론·cloud-init·이미지 다운로드에 필요로 하는 권한 (PVE 8 기준)
PRIVS="Datastore.Allocate Datastore.AllocateSpace Datastore.AllocateTemplate Datastore.Audit \
Pool.Allocate Pool.Audit Sys.Audit Sys.Console Sys.Modify SDN.Use \
VM.Allocate VM.Audit VM.Clone VM.Config.CDROM VM.Config.Cloudinit VM.Config.CPU VM.Config.Disk \
VM.Config.HWType VM.Config.Memory VM.Config.Network VM.Config.Options VM.Migrate VM.Monitor VM.PowerMgmt"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TFVARS="${SCRIPT_DIR}/../terraform/terraform.tfvars"

TOKEN_JSON=$(ssh -o BatchMode=yes "$PVE_HOST" bash -s <<EOF 2>/dev/null
set -e
export LC_ALL=C
pveum role add ${ROLE} -privs "${PRIVS}" 2>/dev/null || pveum role modify ${ROLE} -privs "${PRIVS}"
pveum user add ${PVE_USER} --comment "Terraform (bank-msa-lab)" 2>/dev/null || true
pveum aclmod / -user ${PVE_USER} -role ${ROLE}
pveum user token remove ${PVE_USER} ${TOKEN_NAME} 2>/dev/null || true
# privsep=0: 토큰이 사용자(terraform@pve)의 권한을 그대로 상속. 사용자 자체가 최소 권한이라 안전
pveum user token add ${PVE_USER} ${TOKEN_NAME} --privsep 0 --output-format json
EOF
)

SECRET=$(printf '%s' "$TOKEN_JSON" | python3 -c 'import json,sys; print(json.load(sys.stdin)["value"])')

umask 077
cat > "$TFVARS" <<EOF
# create-terraform-token.sh가 생성. 커밋 금지 (gitignore 대상)
pve_api_token = "${PVE_USER}!${TOKEN_NAME}=${SECRET}"
EOF

echo "OK: ${PVE_USER} / role ${ROLE} / token ${TOKEN_NAME} 발급 완료"
echo "    → $(cd "$(dirname "$TFVARS")" && pwd)/terraform.tfvars 에 기록 (권한 600)"
