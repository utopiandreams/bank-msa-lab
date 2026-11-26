#!/usr/bin/env bash
# 클러스터 DB 5개를 맥북 localhost로 열어 DataGrip 등으로 붙는다 (2026-11-26).
#
#   ./infra/scripts/db-forward.sh          → 전부 열고 접속 정보 출력, Ctrl+C로 닫기
#   ./infra/scripts/db-forward.sh core     → 하나만
#
# 왜 port-forward인가: DB를 LoadBalancer로 집 네트워크에 열면 늘 열려 있다. port-forward는 이 터미널이 켜진 동안만,
# kubectl 권한이 있는 사람만 (API 서버를 거치는 터널). Tailscale로 집 밖에서도 그대로 된다.
# 계정은 각 서비스의 앱 계정(CNPG가 만든 <클러스터>-app Secret)이다 = 쓰기 가능 → DataGrip 데이터 소스에서 "Read-only"를 켠다.
set -uo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lab.env"

# 이름 로컬포트 (5432는 맥북 로컬 Postgres와 겹칠 수 있어 피한다)
ALL="core:15432 transfer:15433 notification:15434 bank-mock:15435 reconciliation:15436"

targets=""
if [ $# -eq 0 ]; then targets=$ALL
else
  for want in "$@"; do
    hit=""
    for pair in $ALL; do [ "${pair%%:*}" = "$want" ] && hit=$pair; done
    [ -z "$hit" ] && { echo "모르는 DB: $want (core | transfer | notification | bank-mock | reconciliation)" >&2; exit 1; }
    targets="$targets $hit"
  done
fi

# 닫을 때 재접속 루프와 그 안의 kubectl까지 한꺼번에 (같은 프로세스 그룹)
cleanup() { trap - EXIT INT TERM; echo; echo "닫았다."; kill 0 2>/dev/null; }
trap cleanup EXIT INT TERM

printf "%-15s %-16s %-15s %-15s %s\n" "DB" "호스트:포트" "데이터베이스" "사용자" "비밀번호"
for pair in $targets; do
  name=${pair%%:*}; port=${pair##*:}; cluster="${name}-db"
  secret=$(kubectl -n bank get secret "${cluster}-app" -o jsonpath='{.data.dbname} {.data.username} {.data.password}') || { echo "${cluster}-app Secret을 못 읽음" >&2; continue; }
  read -r db user pass <<<"$secret"
  printf "%-15s %-16s %-15s %-15s %s\n" "$name" "localhost:$port" "$(base64 -d <<<"$db")" "$(base64 -d <<<"$user")" "$(base64 -d <<<"$pass")"
  # 연결이 끊기면(파드 재시작·노트북 잠자기) 다시 붙는다
  ( while true; do kubectl -n bank port-forward "svc/${cluster}-rw" "$port:5432" >/dev/null 2>&1; sleep 2; done ) &
done
echo
echo "열려 있음. DataGrip: PostgreSQL 데이터 소스 → 위 호스트·포트·계정, Options에서 Read-only 체크. Ctrl+C로 닫기"
wait
