#!/usr/bin/env bash
# Proxmox 호스트 랜카드의 Wake-on-LAN(매직 패킷)을 켜고, 부팅할 때마다 다시 켜지도록 systemd 서비스로 등록한다.
# 꺼진 Proxmox를 맥북에서 원격으로 켜기 위함 (전기료 절약: 실험할 때만 켠다).
#
# 주의: BIOS/UEFI에서도 Wake-on-LAN(또는 "Power On By PCI-E")이 켜져 있어야 동작한다.
# 사용: ./infra/bootstrap/enable-wol.sh
set -euo pipefail

PVE_HOST="${PVE_HOST:-root@172.30.1.200}"
NIC="${NIC:-enp5s0}"

ssh -o BatchMode=yes "$PVE_HOST" bash -s <<EOF
set -e
cat > /etc/systemd/system/wol-${NIC}.service <<UNIT
[Unit]
Description=Enable Wake-on-LAN (magic packet) on ${NIC}
After=network-online.target

[Service]
Type=oneshot
ExecStart=/usr/sbin/ethtool -s ${NIC} wol g

[Install]
WantedBy=multi-user.target
UNIT
systemctl daemon-reload
systemctl enable --now wol-${NIC}.service >/dev/null 2>&1
ethtool ${NIC} | grep -E "^\s+Wake-on:"
EOF
