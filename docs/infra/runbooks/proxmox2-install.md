# 두 번째 Proxmox 호스트 설치 (개발자가 직접)

> 작성: 2026-11-14. 집에 남는 PC(인텔, 32GB, 지금은 Windows·Ubuntu 멀티부팅)에 Proxmox VE를 설치한다.
> 여기까지는 몸으로 하는 일이라 개발자가 하고, **설치가 끝나고 SSH가 되면** 나머지(토큰·Terraform·k3s 조인)는 Claude가 한다.
>
> 왜: 9주 차 관측성 스택의 메모리, 17주 차 "물리 호스트 통째로 다운" 실험 (CLAUDE.md 백로그 "노드 확장").
> 왜 Proxmox: 기존 호스트와 같은 Terraform·cloud-init 방식으로 관리하고, VM 단위로 장애를 낼 수 있어서.

## ⚠️ 시작 전에

- [ ] **이 디스크의 Windows·Ubuntu는 전부 지워진다.** 남길 파일이 있으면 먼저 백업
- [ ] 디스크가 여러 개면, 설치 화면의 "Target Harddisk"에서 **지울 디스크만** 고른다 (다른 디스크는 안 건드림)
- [ ] **유선 랜**을 꽂아 둔다 (Proxmox는 Wi-Fi를 제대로 지원하지 않는다)

## 준비물

- USB 메모리 2GB 이상 (안의 내용은 지워짐)
- 맥북 (USB 굽기용)

## 1. ISO 받기

- https://www.proxmox.com/en/downloads → **Proxmox VE 9.x 최신 ISO Installer**
- 기존 호스트(`172.30.1.200`)는 8.2.2지만 새로 까는 건 9.x. 두 호스트는 **Proxmox 클러스터로 묶지 않으므로**(독립 호스트) 버전이 달라도 된다
  - 묶지 않는 이유: 2대짜리 클러스터는 한 대가 꺼지면 과반수(quorum)를 잃어 남은 한 대도 VM을 관리하지 못한다. Raft·KRaft가 홀수 대를 쓰는 이유와 같다

## 2. USB 굽기 (맥)

**추천: balenaEtcher** (https://etcher.balena.io)
1. Flash from file → 받은 ISO
2. Select target → USB (용량을 보고 맥 디스크가 아닌지 확인)
3. Flash! → 끝나면 "읽을 수 없는 디스크" 경고가 떠도 정상 (무시하고 꺼내기)

> 터미널(`dd`)로도 되지만 디스크 번호를 잘못 고르면 맥 디스크가 날아간다. Etcher를 쓴다.

## 3. BIOS 설정 (새 PC)

부팅할 때 `Del` 또는 `F2` (보드마다 다름)

- [ ] **Intel Virtualization Technology (VT-x)** → Enabled (이게 꺼져 있으면 VM이 안 뜬다)
- [ ] (있으면) **VT-d** → Enabled
- [ ] **Wake-on-LAN** (Power 메뉴, "Power On by PCI-E" 같은 이름일 수 있음) → Enabled
- [ ] 부팅 순서: USB 우선 (또는 부팅할 때 `F11`/`F12` 부트 메뉴에서 USB 선택)

## 4. 설치 화면에 칠 값

USB로 부팅 → **Install Proxmox VE (Graphical)**

| 화면 | 항목 | 값 |
| --- | --- | --- |
| Target Harddisk | 디스크 | 지울 디스크. 파일 시스템은 기본 **ext4** |
| Location | Country / Time zone / Keyboard | South Korea / Asia/Seoul / U.S. English |
| Administration | Password | 기존 호스트와 같은 규칙 (설치 후 SSH 키 인증으로 바꿈) |
| | Email | `root@localhost` (아무거나, 학습엔 무관) |
| **Network** | Management Interface | **유선 랜** (이름이 `enp…`로 시작. `wl…`은 Wi-Fi라 고르지 않는다) |
| | Hostname (FQDN) | `proxmox2.lab` |
| | IP Address (CIDR) | `172.30.1.201/24` |
| | Gateway | `172.30.1.1` |
| | DNS Server | `172.30.1.1` |

- IP를 `.201`로 고른 이유: 기존 호스트 `.200` 옆자리, 공유기 DHCP 범위(`1~100`, `128~148`) 밖, 쿠버네티스 노드(`.230~`)·MetalLB(`.240~.250`)와 겹치지 않음
- 마지막 요약 화면에서 디스크와 IP를 한 번 더 확인 → Install → 끝나면 USB를 뽑고 재부팅

## 5. 설치 확인 (맥북에서)

```bash
ping 172.30.1.201
ssh root@172.30.1.201        # 비밀번호로 접속되면 성공
```

- 웹 UI: `https://172.30.1.201:8006` (인증서 경고는 무시, 사용자 `root`, Realm `Linux PAM`)
- "No valid subscription" 팝업은 무료판이라 뜨는 것. 무시해도 된다 (기존 호스트도 같음)

## 6. 여기까지 되면 Claude에게

"`ssh root@172.30.1.201` 된다"고만 알려 주면, 다음 인프라 세션에서 Claude가 이어서 한다:

1. SSH 키 등록 (비밀번호 로그인 대신 키 인증)
2. 무료 저장소로 전환 (enterprise 저장소 비활성화 → `apt update` 오류 제거)
3. WOL 상시 켜기 (기존 호스트의 `infra/bootstrap/enable-wol.sh` 재사용)
4. 최소 권한 Terraform 사용자·토큰 (`infra/bootstrap/create-terraform-token.sh` 재사용)
5. Terraform에 두 번째 호스트 추가 (provider alias), 워커 VM 생성 → k3s 조인
6. 상태 있는 파드(DB·Kafka)는 기존 호스트에 고정할지 결정 (새 호스트는 필요한 주차에만 켤 예정이라, local-path 디스크가 묶인 파드가 꺼진 노드에 갇히지 않게)
7. `docs/infra/README.md` 기록 (하드웨어 표, IP 계획, 결정)

## 막히면

| 증상 | 확인할 것 |
| --- | --- |
| USB로 부팅이 안 됨 | BIOS의 Secure Boot (Proxmox 9는 지원하지만 오래된 보드면 끄기), 부트 메뉴에서 "UEFI: USB" 선택 |
| 설치 화면에 랜 카드가 안 보임 | 유선 랜 연결, 드라이버 미지원 랜 칩(드묾) → 화면 사진 찍어서 Claude에게 |
| 설치 후 `ping 172.30.1.201` 실패 | 랜 선, 설치 때 고른 인터페이스가 Wi-Fi가 아니었는지, 공유기에서 같은 IP를 다른 기기가 쓰는지 |
| 웹 UI는 되는데 SSH가 안 됨 | 비밀번호 오타, 맥의 `~/.ssh/known_hosts`에 같은 IP의 옛 기록이 있으면 `ssh-keygen -R 172.30.1.201` |
