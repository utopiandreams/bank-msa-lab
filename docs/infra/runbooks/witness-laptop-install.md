# 컨트롤 플레인 중재자 노트북 설치 (개발자가 직접)

> 작성: 2026-11-17. 집에 있던 **Samsung NT455R4J (AMD A6, 2015년쯤)**에 Ubuntu Server를 설치한다.
> 여기까지는 몸으로 하는 일이라 개발자가 하고, **설치가 끝나고 SSH가 되면** 나머지(k3s 내장 etcd 전환·서버 3대)는 Claude가 한다.
>
> 왜: 물리 호스트 2대로는 과반수 HA가 불가능하다 → 세 번째 장애 영역 (`docs/learning/consensus.md`, pitfalls 19).
> 왜 Proxmox가 아니라 Ubuntu Server: VM으로 쪼갤 일이 없고, Proxmox가 메모리를 1~2GB 먹는다. 기존 노드들과 같은 Ubuntu 24.04로 맞춘다.

## ⚠️ 시작 전에

- [ ] 노트북의 **윈도우와 데이터는 전부 지워진다** (비밀번호를 몰라 어차피 못 쓰던 상태)
- [ ] **배터리가 부풀지 않았는지** 다시 확인 (이 노트북은 2026-11-17 확인: 정상). 24시간 충전기에 꽂아 둘 장비라 중요
- [ ] **유선 랜**을 꽂는다. **설치 USB로 부팅하기 전에** 꽂는다 (Proxmox 때처럼 나중에 꽂으면 인식이 늦을 수 있다)

## 1. 사양 확인 (BIOS)

전원을 켜자마자 **`F2` 연타** (삼성 노트북) → 첫 화면에서 확인, 사진을 찍어 Claude에게:
- CPU (AMD A6-…), 메모리 (4GB 이상이면 OK), 저장 장치 모델명 (SSD인지 HDD인지)

BIOS에서 같이 바꿀 것:
- [ ] Boot 순서: **USB 우선** (또는 부팅할 때 `F10`/`Esc` 부트 메뉴에서 USB 선택)
- [ ] Secure Boot: 켜 둬도 Ubuntu는 부팅된다. USB 부팅이 안 되면 끈다
- [ ] (있으면) AC 연결 시 자동 켜기

## 2. ISO와 USB

- ISO: `~/Downloads/ubuntu-24.04.5-live-server-amd64.iso` (Claude가 받아서 체크섬 확인)
- balenaEtcher로 Proxmox 때 쓴 **같은 USB에 다시 굽는다** (덮어쓰므로 따로 지울 필요 없음)
  - Flash from file → 위 ISO / Select target → USB (용량 확인) / Flash!

## 3. 설치 화면에 칠 값

USB로 부팅 → **Try or Install Ubuntu Server**

| 화면 | 항목 | 값 |
| --- | --- | --- |
| Language / Keyboard | | English / English (US) |
| Type of install | | **Ubuntu Server** (minimized 아님) |
| **Network** | 유선 랜 (`enp…`) | **Edit IPv4 → Manual** |
| | Subnet | `172.30.1.0/24` |
| | Address | `172.30.1.202` |
| | Gateway | `172.30.1.254` |
| | Name servers | `1.1.1.1,8.8.8.8` (공유기 DNS는 고정 IP 호스트에 응답하지 않았다, `docs/infra/README.md` 트러블슈팅 2026-11-17) |
| | Search domains | 비움 |
| | Wi-Fi | 설정하지 않는다 |
| Proxy / Mirror | | 기본값 |
| **Storage** | | **Use an entire disk** → 노트북 디스크. "Set up this disk as an LVM group"은 **체크 해제** (LVM이면 기본으로 디스크의 일부만 쓴다. 여기선 필요 없음) |
| **Profile** | Your name | `ubuntu` |
| | Server name | **`bank-cp3`** (컨트롤 플레인 3번: `bank-cp` / proxmox2의 `bank-cp2` / 이 노트북) |
| | Username | `ubuntu` (기존 노드와 같음) |
| | Password | 기억할 수 있는 것 (설치 후 SSH 키 인증으로 접속) |
| Ubuntu Pro | | Skip |
| **SSH** | | **Install OpenSSH server 체크** ✅ (이게 없으면 원격 접속 불가) |
| Featured snaps | | **아무것도 고르지 않는다** (microk8s 등 X. k3s는 Claude가 설치) |

- IP `.202`: 호스트 `.200`·`.201` 옆, 공유기 DHCP 범위(`1~100`, `128~148`) 밖, 노드(`.230~`)·MetalLB(`.240~.250`)와 겹치지 않음
- 끝나면 **Reboot Now** → "Please remove the installation medium" 나오면 USB 빼고 Enter

## 4. 맥에서 SSH 키 등록

맥의 **일반 터미널**에서 (Proxmox 때와 같음):

```bash
ssh-copy-id -i ~/.ssh/id_ed25519.pub ubuntu@172.30.1.202   # yes → 설치 때 비밀번호
ssh ubuntu@172.30.1.202 hostname                            # 비밀번호 없이 bank-cp3 나오면 성공
```

## 5. 덮개를 닫아도 안 꺼지게 (노트북이라 필요)

SSH로 접속해서 (또는 노트북에서 직접):

```bash
sudo sed -i 's/^#\?HandleLidSwitch=.*/HandleLidSwitch=ignore/; s/^#\?HandleLidSwitchExternalPower=.*/HandleLidSwitchExternalPower=ignore/' /etc/systemd/logind.conf
sudo systemctl restart systemd-logind
```

→ 덮개를 닫고 맥에서 `ping 172.30.1.202`가 계속 응답하면 성공. (이 단계는 Claude가 SSH로 대신 해도 된다)

## 6. 여기까지 되면 Claude에게

"`ssh ubuntu@172.30.1.202` 된다"고만 알려 주면, Claude가 이어서 한다:
1. 덮개·절전 설정 확인, 디스크 종류(HDD/SSD)와 쓰기 지연 측정 (etcd 적합성)
2. k3s를 SQLite → 내장 etcd로 전환 (기존 `bank-cp`에서 `--cluster-init`)
3. proxmox2에 `bank-cp2` VM 추가, 노트북 `bank-cp3`을 서버로 조인 → 서버 3대
4. 노트북은 앱 파드가 안 올라가게 taint (컨트롤 플레인 위주)
5. 호스트 하나를 끄고 `kubectl`이 계속 되는지 실측 → 실험 기록
6. `docs/infra/README.md` 기록

## 막히면

| 증상 | 확인할 것 |
| --- | --- |
| USB로 부팅이 안 됨 | 부트 메뉴(`F10`/`Esc`), Secure Boot 끄기, USB를 다른 포트에 |
| 네트워크 화면에 유선 랜이 안 보임 | 랜선을 꽂고 USB로 **다시 부팅** |
| 설치 후 `ping 172.30.1.202` 실패 | 랜선, Network 화면에서 Manual로 넣었는지, 다른 기기가 같은 IP를 쓰는지 |
| `ssh-copy-id`가 거부됨 | 설치 때 OpenSSH server를 체크했는지. 안 했으면 노트북에서 `sudo apt install openssh-server` |
