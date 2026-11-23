#!/usr/bin/env bash
# 실험 클러스터 켜기 (호스트 단위, 병렬)
#   cluster-up.sh            → 전부 (proxmox, proxmox2)
#   cluster-up.sh proxmox    → 하나만
#
# 호스트마다: Wake-on-LAN → SSH 응답 대기 → 그 호스트의 bank-* VM을 한꺼번에 시작.
# 호스트끼리는 서로 기다리지 않는다 (동시에 부팅). 한 대가 안 켜져도 나머지는 계속 간다.
# Wake-on-LAN은 노트북(bank-cp3)이 대신 보낸다. 맥북은 집 안이든 Tailscale로 밖이든 노트북에 SSH만 되면 된다.
set -uo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lab.env"

targets=$(resolve_targets "$@") || exit 2

wake() {   # 매직 패킷: FF x6 + MAC x16, 브로드캐스트로
  # 항상 노트북(bank-cp3)이 보낸다 (2026-11-23): 브로드캐스트는 L2라 Tailscale(L3)을 못 넘는다.
  # 맥북이 집 안이든 밖이든 같은 경로로 켜지게, 집 안에 항상 켜진 노트북을 발신기로 쓴다
  ssh "${SSH_OPTS[@]}" "$WITNESS_HOST" python3 - "$1" "$BROADCAST" <<'PY'
import socket, sys
mac = bytes.fromhex(sys.argv[1].replace(":", ""))
packet = b"\xff" * 6 + mac * 16
s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
s.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
for addr in (sys.argv[2], "255.255.255.255"):
    for port in (9, 7):
        s.sendto(packet, (addr, port))
PY
}

host_up() {
  host_info "$1"
  if host_ssh true 2>/dev/null; then
    log "이미 켜져 있음"
  else
    log "Wake-on-LAN 전송 (${H_MAC}), 부팅 대기 (최대 5분)"
    wake "$H_MAC"
    for _ in $(seq 60); do host_ssh true 2>/dev/null && break; sleep 5; done
    if ! host_ssh true 2>/dev/null; then
      log "❌ 5분 안에 응답 없음. 전원(멀티탭)·랜선·Wake-on-LAN 설정 확인"
      log "   (전원이 완전히 끊겼다 들어온 뒤엔 Wake-on-LAN이 안 먹는 보드가 많다 → 전원 버튼)"
      return 1
    fi
    log "응답함"
  fi

  # bank-* VM 중 꺼진 것을 한꺼번에 시작. 컨트롤 플레인을 먼저 기다릴 필요는 없다:
  # 워커(k3s agent)는 서버가 뜰 때까지 알아서 재접속한다
  # 막 부팅한 호스트는 SSH가 Proxmox 서비스(pmxcfs)보다 먼저 열려 qm list가 실패할 수 있다.
  # 실패를 "빈 목록"으로 읽으면 "시작할 VM 없음"이라고 거짓말하게 된다 (2026-11-23 proxmox2) → 성공할 때까지 재시도
  local list="" ids
  for _ in $(seq 12); do
    list=$(host_ssh "qm list" 2>/dev/null) && break
    list=""; sleep 5
  done
  if [ -z "$list" ]; then
    log "⚠️ 1분 동안 qm list 실패 (Proxmox 서비스가 안 뜸?). VM 상태를 모름"
    return 1
  fi
  ids=$(echo "$list" | awk '$2 ~ /^bank-/ && $3 == "stopped" {print $1}' | tr '\n' ' ')
  if [ -z "${ids// /}" ]; then
    log "시작할 VM 없음"
    return 0
  fi
  if host_ssh "for id in $ids; do qm start \$id & done; wait" >/dev/null 2>&1; then
    log "VM 시작: $ids"
  else
    log "⚠️ VM 시작 중 실패 있음: $ids"
  fi
}

wait_k8s() {   # 컨트롤 플레인이 있는 proxmox가 켜졌을 때만
  H_NAME=k8s
  log "노드 Ready 대기 (최대 5분)"
  local nodes ready total
  for _ in $(seq 60); do
    nodes=$(kubectl get nodes --no-headers --request-timeout=3s 2>/dev/null)
    total=$(echo "$nodes" | grep -c . )
    ready=$(echo "$nodes" | awk '$2=="Ready"' | grep -c . )
    [ "$total" -gt 0 ] && [ "$ready" = "$total" ] && break
    sleep 5
  done
  if [ "${total:-0}" -eq 0 ]; then log "❌ kube-apiserver에 접속 못 함"; return 1; fi
  echo "$nodes" | awk '{printf "    %-8s %s\n", $1, $2}'
  if [ "$ready" = "$total" ]; then
    log "✅ 노드 Ready ${ready}/${total}"
  else
    log "⚠️ 노드 Ready ${ready}/${total} (꺼진 호스트에 있는 노드는 NotReady가 정상)"
  fi
}

# 호스트마다 백그라운드로 동시에
pids=""
for t in $targets; do
  host_up "$t" &
  pids="$pids $t:$!"
done

# proxmox(컨트롤 플레인)가 끝나면 나머지를 기다리지 않고 바로 k8s 확인
# (proxmox2가 응답 없음으로 5분을 다 쓰는 동안 k8s 확인이 늦어지지 않게)
fail=0
for p in $pids; do
  if [ "${p%%:*}" = proxmox ]; then
    if wait "${p#*:}"; then wait_k8s || fail=1; else fail=1; fi
  fi
done
for p in $pids; do
  [ "${p%%:*}" = proxmox ] && continue
  wait "${p#*:}" || fail=1
done

echo
if [ $fail -eq 0 ]; then echo "[$(ts)] ✅ 완료 ($targets)"; else echo "[$(ts)] ⚠️ 일부 실패 (위 ❌ 확인)"; fi
case " $targets " in *" proxmox "*)
  echo "  Argo CD : $ARGOCD_URL"
  echo "  Grafana : $GRAFANA_URL" ;;
esac
exit $fail
