#!/usr/bin/env bash
# Proxmox에 Terraform 전용 최소 권한 사용자/역할/토큰을 만들고,
# 토큰을 infra/terraform/terraform.tfvars에 바로 기록한다 (토큰을 화면에 출력하지 않음).
#
# 왜: 기존 root@pam!terraform 토큰은 privsep=0이라 root 권한 전체를 가졌다.
#     자동화 도구에는 필요한 권한만 준다 (최소 권한 원칙).
#
# 사용: ./infra/bootstrap/create-terraform-token.sh                     # 기존 호스트 (.200)
#       PVE_HOST=root@172.30.1.201 TFVAR=pve2_api_token ./infra/bootstrap/create-terraform-token.sh   # 두 번째 호스트
# 재실행: 역할 권한은 갱신되고, 토큰은 기존 것을 지우고 새로 발급한다.
# tfvars는 해당 변수 한 줄만 바꾼다 (다른 호스트 토큰을 지우지 않게. 2026-11-17 수정: 이전엔 파일을 통째로 덮어씀)
set -euo pipefail

PVE_HOST="${PVE_HOST:-root@172.30.1.200}"
PVE_USER="terraform@pve"
ROLE="TerraformProv"
TOKEN_NAME="provider"
TFVAR="${TFVAR:-pve_api_token}"   # terraform.tfvars에 기록할 변수 이름

# bpg/proxmox 프로바이더가 VM 생성·클론·cloud-init·이미지 다운로드에 필요로 하는 권한
PRIVS="Datastore.Allocate Datastore.AllocateSpace Datastore.AllocateTemplate Datastore.Audit \
Pool.Allocate Pool.Audit Sys.Audit Sys.Console Sys.Modify SDN.Use \
VM.Allocate VM.Audit VM.Clone VM.Config.CDROM VM.Config.Cloudinit VM.Config.CPU VM.Config.Disk \
VM.Config.HWType VM.Config.Memory VM.Config.Network VM.Config.Options VM.Migrate VM.PowerMgmt"
# 버전 차이: PVE 9에서 VM.Monitor가 없어지고 VM.GuestAgent.*로 쪼개졌다 (게스트 에이전트로 VM IP를 읽는 권한)
PVE_MAJOR=$(ssh -o BatchMode=yes "$PVE_HOST" "pveversion" 2>/dev/null | sed -E 's#^pve-manager/([0-9]+).*#\1#')
if [ "${PVE_MAJOR:-8}" -ge 9 ]; then PRIVS="$PRIVS VM.GuestAgent.Audit"; else PRIVS="$PRIVS VM.Monitor"; fi

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

# 해당 변수 줄만 교체(없으면 추가). 토큰은 화면에 찍지 않는다
umask 077
[ -f "$TFVARS" ] || echo "# create-terraform-token.sh가 관리. 커밋 금지 (gitignore 대상)" > "$TFVARS"
TFVARS="$TFVARS" TFVAR="$TFVAR" LINE="${TFVAR} = \"${PVE_USER}!${TOKEN_NAME}=${SECRET}\"" python3 -c '
import os, re
p, name, line = os.environ["TFVARS"], os.environ["TFVAR"], os.environ["LINE"]
s = open(p).read()
pat = re.compile(r"^" + re.escape(name) + r"\s*=.*$", re.M)
s = pat.sub(lambda m: line, s) if pat.search(s) else s.rstrip("\n") + "\n" + line + "\n"
open(p, "w").write(s)
'

echo "OK: ${PVE_HOST} (PVE ${PVE_MAJOR}) / ${PVE_USER} / role ${ROLE} / token ${TOKEN_NAME} 발급 완료 → ${TFVAR}"
echo "    → $(cd "$(dirname "$TFVARS")" && pwd)/terraform.tfvars 에 기록 (권한 600)"
