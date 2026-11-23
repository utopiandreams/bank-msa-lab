# 인프라 노트

인프라의 현재 상태, 결정한 것과 그 이유, 트러블슈팅을 한곳에 모은다. 인프라 작업 전에 먼저 읽고, 작업 후에는 반드시 갱신한다.

> `TBD` = 아직 확인하지 않음. 추측으로 채우지 않는다.
> 마지막 확인: 2026-11-11 (SSH로 Proxmox 호스트를 직접 조회)

## 1. 물리 서버 (Proxmox 호스트)

| 항목 | 값 |
| --- | --- |
| 호스트 | `proxmox` / `172.30.1.200` (맥북에서 `ssh root@172.30.1.200`, 키 인증) |
| 하이퍼바이저 | Proxmox VE 8.2.2 (kernel 6.8.4-2-pve) |
| CPU | Intel Xeon E5-2697 v3 × 1소켓 (14코어 / 28스레드, 2.6GHz) |
| 메모리 | 32GB (가용 약 29GB), swap 8GB |
| 디스크 | NVMe SSD 1TB 1개 |
| 스토리지 | `local` (dir, 약 94GB, ISO·스니펫), `local-lvm` (LVM-thin, 약 794GB, VM 디스크). ZFS 없음 → ARC 메모리 부담 없음 |
| 네트워크 | 브리지 `vmbr0` (enp5s0), 서브넷 `172.30.1.0/24`, 게이트웨이·DNS `172.30.1.254` |
| 공유기 DHCP 범위 | `172.30.1.1~100`, `172.30.1.128~148` → **고정 IP는 이 범위 밖에서만** 쓴다 |

참고: SSH로 접속하면 perl locale 경고가 나온다. 동작에는 문제가 없다.

### 두 번째 호스트 `proxmox2` (2026-11-17 설치)

| 항목 | 값 |
| --- | --- |
| 호스트 | `proxmox2` / `172.30.1.201` (`ssh root@172.30.1.201`, 키 인증) |
| 하이퍼바이저 | Proxmox VE 9.2.28 (설치 9.2.2 → 바로 dist-upgrade), 무료 저장소(`pve-no-subscription`) |
| CPU | Intel Core i5-14600K (14코어: P 6 + E 8 / 20스레드) |
| 메모리 | DDR5 32GB (가용 약 31GB) |
| 디스크 | WD_BLACK SN850X NVMe 1TB |
| 네트워크 | `nic0` → 브리지 `vmbr0`. Proxmox 9의 **인터페이스 이름 고정**(pin, MAC 기준) 사용 — 커널·BIOS 업데이트로 `eno1` 같은 이름이 바뀌어 네트워크가 끊기는 사고 방지 |
| DNS | `1.1.1.1`, `8.8.8.8`, `172.30.1.254` 순 (아래 트러블슈팅) |
| WOL | `nic0` MAC `e8:9c:25:3d:77:19`, systemd 서비스 `wol-nic0` |
| 관계 | 기존 호스트와 **Proxmox 클러스터로 묶지 않음** (2대는 quorum 문제). Terraform이 두 호스트에 각각 붙는다 |
| 설치 절차 | `runbooks/proxmox2-install.md` |

### 컨트롤 플레인 중재자 노트북 `bank-cp3` (2026-11-18 설치)

| 항목 | 값 |
| --- | --- |
| 장비 | Samsung NT455R4J 노트북 (2015년쯤, 집에 있던 것) |
| OS | Ubuntu Server 24.04.5 (Proxmox 없이 바로. VM으로 쪼갤 일이 없고 Proxmox가 메모리를 1~2GB 먹어서) |
| 접속 | `ssh ubuntu@172.30.1.202` (설치 때 GitHub 공개키를 가져옴, **키 인증만**: 비밀번호 로그인 끔), sudo는 비밀번호 없음 (기존 노드와 같음) |
| CPU / 메모리 | AMD A6 4코어 / 6.7GB (8GB 중 일부를 내장 그래픽이 씀) |
| 디스크 | `SAMSUNG MZ7TE128` (PM851, 2.5인치 SATA SSD, 128GB) |
| 네트워크 | `enp2s0` 유선, `172.30.1.202/24`, DNS `1.1.1.1`·`8.8.8.8` |
| 노트북 설정 | 덮개 닫아도 동작(`HandleLidSwitch=ignore`), 절전 타깃 mask, 시간대 Asia/Seoul. 배터리 정상(부풀지 않음) → 정전 때 UPS 역할 |
| 역할 (예정) | k3s 서버 3번 (etcd 멤버, 앱 파드는 taint로 막음), 항상 켜 두는 Tailscale 관문 + WOL 발신기 |
| 설치 절차 | `runbooks/witness-laptop-install.md` |

**etcd 디스크 적합성 측정 (2026-11-18, etcd 문서의 fio 방법: 2300바이트 쓰기 + fdatasync 반복, 22MB)**

| 지표 | `bank-cp3` (PM851 SATA SSD) | etcd 권장 |
| --- | --- | --- |
| fdatasync p50 | 4.6ms | |
| fdatasync p99 | **16.6ms** | **< 10ms** |
| fdatasync p99.9 | 18.2ms | |
| 쓰기 IOPS | 202 | |

- 판단: 권장 기준 미달이지만 **중재자로는 쓴다.** etcd는 과반수만 확정하면 되므로 나머지 두 멤버가 빠르면 커밋은 그 둘로 이뤄진다. 선출 타임아웃(1000ms)에 비해 17ms 꼬리는 리더 선출을 일으킬 수준이 아니다. 약점: 이 노트북이 **리더가 되면** 쓰기가 이 디스크 속도를 따른다
- 원인: 보급형 SSD는 전원 손실 보호(PLP)가 없어 fdatasync마다 실제 플래시 쓰기를 기다린다. 평균은 괜찮고 p99만 튀는 전형적인 꼬리
- 다음: HA 구성 후 `etcd_disk_wal_fsync_duration_seconds` 관찰. 문제 시 SSD 교체. 같은 측정을 proxmox2 NVMe·기존 VM 디스크에도 돌려 비교표

### 리소스 관점의 제약
- **병목은 CPU가 아니라 메모리(32GB)다.** 28스레드면 CPU는 넉넉하다.
- 꺼져 있는 VM은 메모리를 쓰지 않는다. 디스크만 차지한다.
- 최종 단계에서 올라갈 것들의 대략적인 메모리 추정 (실측으로 갱신할 것):

| 구성 요소 | 추정 |
| --- | --- |
| Proxmox 호스트 자체 | ~2GB (실측 1.8GB) |
| k8s 시스템 컴포넌트 (노드당) | 0.5~1GB |
| Spring 서비스 7개 (JVM) | 3.5~4GB |
| PostgreSQL (서비스별) | ~1GB |
| Kafka (Strimzi, KRaft, 브로커 3) | ~3GB |
| Redis | ~0.5GB |
| 관측성 (Prometheus, Loki, Tempo, Grafana, OTel Collector) | 3~4GB |
| Argo CD | 0.5~1GB |
| Chaos Mesh, k6-operator, k6 실행 | 1GB 이상 |

→ 빠듯하지만 가능한 수준. 메모리가 부족해지면 1) 관측성 스택 retention 축소, 2) Kafka 브로커 수 축소, 3) 메모리 증설(DDR4 ECC RDIMM) 순으로 검토한다.

## 2. 이전 자원 정리 (2026-11-11)

이전 프로젝트(`concurrency-conquest`, 태그 `vaccount-lab`)의 자원은 이 프로젝트에 집중하기 위해 정리했다.

- [x] VM 210~221 (k3s-cp, k3s-w1, k3s-w2, svc-db, hecto)과 스니펫: 이전 프로젝트에서 `terraform destroy`로 삭제
- [x] 수동으로 만든 템플릿 VM 9000 (`ubuntu-2404-tpl`): `qm destroy 9000 --purge` (개발자가 직접 실행)
- [x] 기존 토큰 `root@pam!terraform` (privsep=0, root 권한 전체): 새 최소 권한 토큰이 동작하는지 확인한 뒤 삭제

## 3. 클러스터 구성

### 현재 상태 (2026-11-11)
- [x] 최소 권한 토큰 발급 (`terraform@pve!provider`)
- [x] `terraform apply`: 리소스 10개 생성, 약 2분 (이미지 다운로드 47초 + VM 생성 약 1분). cloud-init으로 k3s를 설치해 노드 4대 모두 Ready (`v1.37.2+k3s1`, Ubuntu 24.04.5, containerd 2.3.7)
- [x] kube-system 애드온: coredns, local-path(기본 StorageClass), metrics-server, Traefik. k3s가 Gateway API CRD도 함께 설치함
- [x] Argo CD v3.6.1 (Helm 차트 10.17.1): `infra/bootstrap/install-argocd.sh`로 최초 1회 설치, root 앱 등록 (App of Apps)
- [x] MetalLB 0.16.2 (L2 모드, FRR-K8s 비활성화): Argo CD가 설치. Traefik이 `172.30.1.240`을 받음
- [x] hello(whoami) + Ingress: `http://hello.172.30.1.240.nip.io` 응답 확인
- [x] ARP 확인: 맥북의 `.240` MAC(`bc:24:11:90:48:f0`) = bank-w3의 MAC → bank-w3의 speaker가 대표로 대답 중
- [x] 개발자 직접 확인: Git 수정(hello v2, replicas 3) → push → 자동 반영 / `kubectl delete deployment` → Argo CD가 몇 초 안에 재생성 (self-heal)
- [x] Argo CD admin 비밀번호 변경, 초기 비밀번호 Secret 삭제
- [x] (2026-11-12) core 서비스 첫 배포: GitHub Actions가 `ghcr.io/utopiandreams/core:<sha>` push + values 태그 커밋 → Argo CD가 `bank` 네임스페이스에 배포 → `http://core.172.30.1.240.nip.io/actuator/health` UP. 이미지 pull부터 Ready까지 약 14초. GHCR 패키지는 public 상태에서 pull 성공

### 노드

| VMID | 이름 | 역할 | IP | vCPU / RAM / 디스크 |
| --- | --- | --- | --- | --- |
| 230 | bank-cp | k3s server (taint `CriticalAddonsOnly`, 앱 파드 안 올라감) | 172.30.1.230 | 2 / 4GB / 30GB |
| 231 | bank-w1 | k3s agent | 172.30.1.231 | 6 / 8GB / 50GB |
| 232 | bank-w2 | k3s agent | 172.30.1.232 | 6 / 8GB / 50GB |
| 233 | bank-w3 | k3s agent | 172.30.1.233 | 6 / 8GB / 50GB |
| 234 | bank-w4 | k3s agent (**proxmox2**) | 172.30.1.234 | 10 / 12GB / 80GB |
| 235 | bank-w5 | k3s agent (**proxmox2**) | 172.30.1.235 | 10 / 12GB / 80GB |

- proxmox: 합계 28GB, 호스트에 약 4GB 남음. proxmox2: 합계 24GB, 약 7GB 남음 (그중 약 5GB는 HA 때 두 번째 컨트롤 플레인 자리). 메모리는 벌루닝 없이 고정 할당한다.
- 모든 노드에 `topology.kubernetes.io/zone=<물리 호스트>` 라벨 (`proxmox` / `proxmox2`). AWS의 가용 영역(AZ) 라벨과 같은 이름이라 `topologySpreadConstraints`·anti-affinity를 그대로 쓸 수 있다 (17주 차 "호스트 통째로 다운")

### 노드 확장 (2026-11-18, proxmox2에 워커 2대)

**VM 구성 결정:** 워커 2대 × (vCPU 10 / 12GB). 3대 이상으로 쪼개지 않은 이유:
- 호스트 메모리(31GB)가 한계다. VM 개수를 늘려도 합계는 그대로고, VM마다 OS·kubelet·DaemonSet(flannel, node-exporter, svclb)이 0.5~1GB씩 고정으로 먹는다
- 작은 노드가 여러 개면 합계로는 남는데 한 덩어리로는 없는 **조각화**가 생긴다 (4GB 파드가 3GB씩 남은 노드 어디에도 못 들어감)
- 같은 호스트 위의 VM은 호스트와 함께 죽는다 → VM을 늘려도 호스트 장애 대비는 안 된다. 2대인 이유는 "노드 하나를 빼도(drain·커널 업데이트·노드 장애 실험) 그 호스트에 워커가 남게"
- vCPU는 10 × 2 = 호스트 스레드 20. CPU는 VM끼리 나눠 쓰는 자원이라 꽉 채워도 된다
- **주의:** proxmox2(i5-14600K, 최대 5.3GHz)가 proxmox(E5-2697 v3, 최대 3.6GHz)보다 코어당 훨씬 빠르다. 같은 서비스도 어느 노드에 떴는지에 따라 지연이 다르다 → k6 재측정 때 파드 배치 노드를 같이 기록한다

**Terraform 구조 변경:**
- 두 호스트는 Proxmox 클러스터가 아니라 API 주소가 따로다 → 프로바이더 2개 (`proxmox`, `proxmox.pve2`). 프로바이더는 `for_each` 안에서 고를 수 없어서, 호스트 하나 분량(이미지·스니펫·VM)을 모듈 `modules/proxmox-host`로 빼고 호스트마다 한 번씩 부른다
- 노드 표(`main.tf` `locals.nodes`)에 `host`·`extra_labels` 칸. 노드 추가는 이 표에 한 줄
- 기존 루트 리소스는 `moved` 블록으로 모듈 안으로 주소만 옮겼다
- **기존 VM이 다시 만들어지지 않게** (pitfalls 25): cloud-init 스니펫은 생성 때만 쓰는 값이라 내용이 바뀌면 VM 교체다. 템플릿에 `extra_labels` 반복문을 넣되 기존 4대는 `[]`로 두고, 옛/새 템플릿 렌더 결과가 같은지 `terraform console`로 확인(`true`). 기존 노드 zone 라벨은 `kubectl label`로
- plan: **9 moved, 5 to add, 0 to change, 0 to destroy** 확인 후 그 plan 파일로 apply. 적용 전 상태 백업 `terraform.tfstate.before-pve2` (gitignore)
- proxmox2 준비: `local` 스토리지에 `snippets` 콘텐츠 허용 (`pvesm set local --content iso,vztmpl,backup,import,snippets`). Terraform 토큰은 설치 때 발급해 둔 `pve2_api_token`

### IP 계획

| 용도 | 범위 |
| --- | --- |
| 공유기 DHCP (사용 금지) | .1~100, .128~148 |
| Proxmox 호스트 | .200 (proxmox), .201 (proxmox2), .202 (노트북 bank-cp3) |
| k8s 노드 | .230~.239 |
| MetalLB LoadBalancer 풀 | .240~.250 |
| 게이트웨이 / DNS | .254 |

### 구성 요소

| 항목 | 결정 | 이유 |
| --- | --- | --- |
| VM 프로비저닝 | Terraform + `bpg/proxmox` (v0.121) + cloud-init 스니펫 | 이전 프로젝트에서 검증된 방식 |
| OS 이미지 | Ubuntu 24.04 클라우드 이미지를 Terraform이 직접 다운로드 | 수동 템플릿 없이, 빈 Proxmox에서 `apply` 한 번으로 재현 |
| k8s 배포판 | k3s `v1.37.2+k3s1` (버전 고정) | 메모리 32GB 제약, 본게임은 MSA. CNCF 인증 k8s라 개념이 그대로 통함 |
| Proxmox 인증 | 최소 권한 사용자 `terraform@pve` + 역할 `TerraformProv` + 토큰 `provider` | 기존 토큰은 root 권한 전체. 최소 권한 원칙 |
| StorageClass | k3s 기본 `local-path` | 가장 가볍다. 단점: 노드에 묶여서, 노드가 죽으면 해당 PV도 사용 불가 → 16주 차 노드 장애 실험 때 재검토 |
| LoadBalancer | MetalLB (L2), k3s servicelb는 비활성화 | 실무 온프레미스의 표준적인 선택 |
| Ingress | k3s 기본 Traefik | 추가 설치 없음. Gateway API로 전환은 필요할 때 검토 |
| GitOps | Argo CD, App of Apps (`deploy/argocd/root.yaml` → `deploy/argocd/apps/`) | 확정. 최초 1회만 Helm으로 설치하고, 이후 모든 설치는 Git으로 |
| Git 저장소 | `git@github.com:utopiandreams/bank-msa-lab.git` | 확정 |
| 컨테이너 이미지 | 공통 Dockerfile 하나 (`deploy/docker/spring-boot.Dockerfile`): jar는 Gradle이 만들고 Docker는 레이어로 풀어서 포장만, `eclipse-temurin:25-jre`, 비root(uid 10001), `MaxRAMPercentage=75` | 내부가 다 보여서 설명하기 쉽다 (Jib·Buildpacks는 편하지만 감춰짐). 코드만 바뀐 재배포는 수십 KB 레이어만 새로 받는다 |
| 레지스트리 | GHCR (`ghcr.io/utopiandreams/<서비스>`, public) | 무료, GitHub Actions에서 토큰 없이 push, public이라 클러스터에 pull secret 불필요 |
| 이미지 태그 갱신 | CI(GitHub Actions)가 values 파일의 태그를 커밋 해시로 고쳐 커밋 | 가장 흔한 방식. Argo CD Image Updater는 구성 요소가 하나 늘고 메모리도 빠듯하다. `latest`는 쓰지 않는다 |
| 서비스 매니페스트 | 공통 Helm 차트 하나(`deploy/helm/spring-service`) + 서비스별 values | 서비스 6개의 Deployment·Service·Ingress가 거의 같다. 프로브·리소스 제한을 빠뜨린 서비스가 구조적으로 안 나온다 |
| PostgreSQL | CloudNativePG 오퍼레이터 1.30.1 (차트 0.29.1) + 서비스마다 `Cluster` 하나 (`deploy/platform/<서비스>-db`) | 쿠버네티스 위 Postgres의 사실상 표준. 계정·Secret·접속 Service·페일오버를 오퍼레이터가 맡는다. 17주 차 DB 다운 실험에서 instances를 늘려 페일오버를 본다. DB 앱은 `prune: false`로 Git에서 지워도 자동 삭제되지 않게 했다 |

### 사용법

```bash
# 0) (최초 1회) 최소 권한 토큰 발급 → infra/terraform/terraform.tfvars 자동 생성
./infra/bootstrap/create-terraform-token.sh

# 1) VM 생성 + k3s 설치 (cloud-init이 첫 부팅 때 설치, 수 분 소요)
cd infra/terraform && terraform init && terraform apply

# 2) kubeconfig 가져오기 (~/.kube/bank-msa-lab.yaml)
./infra/scripts/fetch-kubeconfig.sh
export KUBECONFIG=~/.kube/bank-msa-lab.yaml
kubectl get nodes -o wide

# 3) Argo CD 설치 + root 앱 등록 (최초 1회). 이후 MetalLB·서비스 등은 Argo CD가 Git을 보고 설치
./infra/bootstrap/install-argocd.sh

# 전부 지우고 다시 만들기
terraform destroy && terraform apply   # 이후 2), 3) 다시 실행
```

### 전원 관리 (전기료 절약: 실험할 때만 켠다)

| 동작 | 스크립트 | Raycast |
| --- | --- | --- |
| 켜기: 호스트별 Wake-on-LAN → 그 호스트의 `bank-*` VM 한꺼번에 시작 → 노드 Ready 대기 | `infra/scripts/cluster-up.sh [all\|proxmox\|proxmox2]` | Bank Lab Up 🟢 (대상 드롭다운) |
| 끄기: 호스트별 워커 → 컨트롤 플레인 정상 종료 → 호스트 `poweroff` | `infra/scripts/cluster-down.sh [all\|proxmox\|proxmox2]` | Bank Lab Down 🔴 (대상 드롭다운, 확인 창 있음) |
| 상태: 물리 장비 3대(proxmox·proxmox2·노트북 전원/배터리) / VM / 노드·메모리 / Argo CD 앱 | `infra/scripts/cluster-status.sh` | Bank Lab Status 📊 |
| Argo CD 화면 열기 (Chrome) | `infra/scripts/raycast/bank-argocd.sh` | Bank Argo CD 🐙 |
| Grafana 화면 열기 (Chrome) + admin 비밀번호 클립보드 복사 | `infra/scripts/raycast/bank-grafana.sh` | Bank Grafana 📈 |

- Raycast 등록: Raycast 설정 → Extensions → Script Commands → Add Directories → `infra/scripts/raycast` 선택
- 공통 설정: `infra/scripts/lab.env` (IP, MAC, VMID, PATH)
- **호스트 단위 제어 + 병렬 (2026-11-18 변경):** 대상은 `all`(기본) / `proxmox` / `proxmox2`. 호스트마다 백그라운드로 동시에 진행하고, 출력 줄 앞에 `[호스트]`가 붙는다. 한 대가 안 켜져도 나머지는 계속 간다.
  - 켜기: VM은 컨트롤 플레인을 기다리지 않고 한꺼번에 시작한다 (k3s agent가 서버에 알아서 재접속). k8s Ready 확인은 컨트롤 플레인이 있는 proxmox가 끝나는 즉시 한다 (proxmox2의 5분 대기에 묶이지 않게).
  - 끄기: 호스트 안에서는 워커들 동시에 → 컨트롤 플레인 순서 유지. 호스트끼리는 동시.
  - VM은 이름이 `bank-`로 시작하는 것만 다룬다 (호스트에 다른 VM이 있어도 안 건드림). proxmox2에 VM이 생기면 스크립트 수정 없이 같이 켜지고 꺼진다.
  - 노트북은 항상 켜 두는 중재자라 up/down 대상이 아니고 상태만 본다. 전원선이 빠지면 상태에 ⚠️ 배터리 동작으로 표시된다.
  - HA(서버 3대) 뒤에는 "하나만 끄기" 전에 etcd 과반수가 남는지 따져야 한다 (`cluster-down.sh` 머리 주석).
  - Raycast는 PATH가 짧아 macOS 기본 `/bin/bash` 3.2로 돌 수 있다 → 연관 배열·`wait -n` 없이 작성.
- Wake-on-LAN: Proxmox `enp5s0` (MAC `22:02:4d:07:2d:06`) 지원 모드 `pumbg`. 기본값이 `d`(꺼짐)라 `infra/bootstrap/enable-wol.sh`로 `wol g`를 켜고, systemd 서비스 `wol-enp5s0`로 부팅마다 다시 켜지게 했다.
  - 매직 패킷은 브로드캐스트라 같은 LAN에서 보내야 한다 → 2026-11-23부터 **항상 노트북 bank-cp3가 대신 보낸다** (`cluster-up.sh` `wake()`가 SSH로 bank-cp3에서 실행). 맥북은 집 안이든 Tailscale로 밖이든 bank-cp3에 SSH만 되면 된다.
  - 2026-11-11 실제 테스트: Bank Lab Down으로 종료 → Bank Lab Up으로 원격 부팅 성공. **BIOS 설정 변경 없이 동작했다.** 재부팅 후 노드 4대 Ready, Argo CD 앱 4개 Synced/Healthy, hello v2 응답까지 자동으로 복구됐다.
  - 종료는 VM을 하나씩 정상 종료하느라 1~2분 걸린다 (바로 꺼지지 않는다고 당황하지 말 것).
- **원격 접속 (Tailscale, 2026-11-23):** 공유기 포트는 하나도 열지 않는다.
  - bank-cp3 = 서브넷 라우터: `tailscale up --advertise-routes=172.30.1.0/24 --hostname=bank-cp3`, IP 포워딩은 `/etc/sysctl.d/99-tailscale.conf`. 관리 콘솔에서 경로 승인 + 키 만료 끔. Tailscale 주소 `100.100.83.88`
  - 맥북: Tailscale 앱(`brew install --cask tailscale`), 같은 계정. kubeconfig·ssh·nip.io 주소를 바꾸지 않고 그대로 쓴다 (집 안에서는 와이파이 경로가 이겨서 Tailscale을 안 탄다)
  - `cluster-status.sh`도 노트북을 거친다: 노트북 먼저(못 붙으면 "Tailscale 확인"을 띄우고 멈춤, 꺼짐과 못 닿음을 구분하려고) → 호스트·컨트롤 플레인 VM은 노트북에서 ping → 켜진 호스트만 `ssh -J`(노트북 점프)로 VM 목록. 컨트롤 플레인이 꺼져 있으면 kubectl을 건너뛴다 (재시도로 22초 → 9초)
  - 확인 (2026-11-23): 집 안에서 맥북 → bank-cp3 직접 연결 8ms (릴레이 아님). 밖(카페 `192.168.219.0/24`)에서도 직접 연결 21ms, `ssh ubuntu@172.30.1.202`·`cluster-status.sh` 동작. **밖에서 꺼진 클러스터 켜기 성공**: 노트북 WOL → 두 호스트 부팅 → 노드 6대 Ready, Argo CD 앱 전부 Synced/Healthy (약 2분)
  - 고친 구멍 (2026-11-23): 막 부팅한 호스트는 SSH가 Proxmox 서비스보다 먼저 열려 `qm list`가 실패할 수 있는데, 스크립트가 실패를 빈 목록으로 읽어 "시작할 VM 없음"이라고 했다 → 성공할 때까지 재시도, 1분 넘으면 ⚠️
  - 한계: 밖의 LAN도 `172.30.1.0/24`(KT 기본 대역)이면 겹쳐서 못 들어간다 (pitfalls 35). 그땐 `ssh ubuntu@100.100.83.88`로 점프
  - 다른 사람에게 공개(시연)는 Cloudflare Tunnel + Access로 따로 (CLAUDE.md 백로그). 원리: `docs/learning/tailscale-and-nat.md`
- VM은 `on_boot = false`라서 호스트만 켜면 VM은 꺼진 채로 있다 → cluster-up.sh가 켠다.

### 트레이싱 (9주 차, 2026-11-23)

서비스(OTel Java 에이전트) → OTel Collector → Tempo ← Grafana (데이터 소스 Tempo). 개념·AWS 대응은 `docs/learning/observability.md` 5-0.

| Argo CD 앱 / 위치 | 내용 | wave |
| --- | --- | --- |
| `tempo` | grafana-community 차트 3.2.1 (Tempo 3.1.1), 단일 바이너리, 보관 72h, PVC 10Gi(local-path), `monitoring` | 1 |
| `otel-collector` | 차트 0.178.2 (Collector 0.163.2, `-k8s` 배포판), OTLP → memory_limiter → filter/noise → batch → Tempo | 1 |
| 공통 차트 `tracing.*` | init 컨테이너가 에이전트 2.33.1 jar를 복사, `JDK_JAVA_OPTIONS=-javaagent`, 지표·로그 내보내기 끔, 샘플링 전부 | 앱 |
| `kube-prometheus-stack` | Grafana `additionalDataSources`에 Tempo (uid `tempo`) | 0 |

결정과 이유:
- **에이전트는 init 컨테이너로**: 앱 이미지를 다시 빌드하지 않고 버전을 차트 한 곳에서. OTel Operator 자동 주입은 웹훅·인증서가 따라와 지금 규모엔 과함
- **`JDK_JAVA_OPTIONS`로 붙인다**: `JAVA_TOOL_OPTIONS`는 이미지가 힙 설정(`MaxRAMPercentage=75`)에 쓰고 있어 덮어쓰면 힙 설정이 사라진다
- **서비스 메모리 512Mi → 640Mi**: 에이전트를 붙이니 유휴 상태에서 core 454Mi·transfer 458Mi (전엔 361~385Mi). 7주 차 이전 실험 수치와 비교할 때 조건 차이를 적는다
- **잡음 제거는 Collector에서**: 프로브(`/livez`·`/readyz`)·`/actuator/**`가 몇 초마다 트레이스를 만든다. 에이전트엔 경로 제외 설정이 없어 Collector `filter` 프로세서로 버린다
- **Collector 값은 문자열 `values`**: `valuesObject`는 Argo CD가 `null`을 버려 기본값을 못 지운다 (pitfalls 38)
- Collector 자체 지표 포트(8888)는 파드 IP에만 열려 port-forward로 안 닿는다 (확인할 땐 Tempo 쪽에서)
- 확인: Grafana → Explore → Tempo → Search(Service Name) 또는 TraceQL. API: `kubectl -n monitoring port-forward svc/tempo 3200` → `/api/search?q={...}`, `/api/traces/<id>`
- 첫 확인 (2026-11-23): gateway → transfer → core 송금 한 건이 span 39개 한 트레이스. Kafka 구간은 에이전트가 헤더(`traceparent`)로 이어 주지만, **Outbox에서 끊긴다** (요청 트레이스는 outbox INSERT로 끝, 릴레이가 새 트레이스로 발행 → notification은 릴레이 트레이스에 붙음). 해결은 9주 차 4단계

### 배포 구조 (GitOps)

```
deploy/
├── argocd/
│   ├── bootstrap/values.yaml   # Argo CD 자체 Helm 설정 (최초 설치용)
│   ├── root.yaml               # App of Apps 뿌리 (최초 1회 kubectl apply)
│   └── apps/                   # 여기에 Application 파일을 추가하면 Argo CD가 설치
│       ├── metallb.yaml          (wave 0, 공식 Helm 차트)
│       ├── metallb-config.yaml   (wave 1, IP 풀)
│       ├── cnpg-operator.yaml    (wave 0, CloudNativePG 공식 차트)
│       ├── core-db.yaml          (wave 1, core 전용 Postgres)
│       ├── core.yaml             (wave 2, 공통 차트 + core values → 네임스페이스 bank)
│       ├── transfer-db.yaml      (wave 1, transfer 전용 Postgres)
│       ├── transfer.yaml         (wave 2, 공통 차트 + transfer values)
│       ├── redis.yaml            (wave 1, 분산 락 저장소 StatefulSet, 2026-11-22)
│       └── ... gateway, notification(+db), bank-mock, kafka, 관측성 (파일 목록이 곧 설치 목록)
├── platform/metallb-config/    # IPAddressPool, L2Advertisement
├── platform/core-db/           # CloudNativePG Cluster (Secret core-db-app, Service core-db-rw)
├── platform/transfer-db/       # CloudNativePG Cluster (Secret transfer-db-app, Service transfer-db-rw)
├── docker/spring-boot.Dockerfile  # 모든 Spring 서비스 공통 Dockerfile
├── helm/spring-service/        # 모든 Spring 서비스 공통 차트 (Deployment, Service, Ingress)
└── apps/
    ├── core/values.yaml        # core 배포 설정. image.tag는 CI가 갱신
    ├── transfer/values.yaml    # transfer 배포 설정 (2026-11-13 추가)
    └── <서비스>/values.yaml     # gateway, notification, bank-mock (서비스마다 하나)
```

서비스 배포 흐름 (`.github/workflows/services.yaml`):
```
services/ 등 push → 테스트 → amd64 이미지 → ghcr.io/utopiandreams/<서비스>:<커밋해시>
  → deploy/apps/<서비스>/values.yaml의 tag 커밋 (github-actions[bot]) → Argo CD 반영 → 롤링 업데이트
```

새 서비스 추가: ① 워크플로 `matrix.service`에 이름 추가 ② `deploy/apps/<서비스>/values.yaml` ③ `deploy/argocd/apps/<서비스>.yaml` (core.yaml 복사). GHCR 패키지는 손댈 필요 없다: public 레포에서 Actions가 올린 패키지는 레포에 연결되어 public으로 생긴다 (2026-11-14 gateway 추가 때 익명 pull 200으로 확인).

### gateway JWT 비밀키 (2026-11-14)

- 서명 비밀키는 Git에 두지 않는다. 클러스터에서 **한 번** 만들고, 차트의 `secretEnv`로 `GATEWAY_JWT_SECRET`에 주입한다 (ADR-0002).
  ```bash
  kubectl -n bank create secret generic gateway-jwt --from-literal=secret="$(openssl rand -base64 48)"
  ```
- 64바이트 랜덤 (HS256은 32바이트 이상 필요, 짧으면 gateway가 기동을 거부한다).
- **한계:** GitOps 밖에 있는 유일한 리소스라, 클러스터를 새로 만들면 이 명령을 다시 쳐야 한다. 키를 바꾸면 이미 발급된 토큰이 전부 무효가 된다 (15분이면 자연히 만료되니 학습용으로는 감수). 실무 대안: Sealed Secrets(암호화해서 Git에 커밋), External Secrets Operator(Vault·클라우드 비밀 저장소에서 동기화).

공통 차트의 실무 포인트:
- **CPU limit 없음, 메모리 limit만.** JVM 기동 때 CPU 스로틀링 → startupProbe 실패 → 재시작 루프를 피한다. 힙은 메모리 limit의 75%.
- **프로브 3종.** startup(기동 끝날 때까지 나머지 보류) / liveness(실패 시 재시작 → 재시작으로 낫는 것만) / readiness(실패 시 Service에서 제외). 경로는 앱 포트의 `/livez`·`/readyz` (2026-11-18, actuator는 관리 포트 8081).
- **종료 순서.** preStop sleep 5초(엔드포인트 제거가 Traefik까지 퍼질 시간) → SIGTERM → Spring graceful shutdown → `terminationGracePeriodSeconds` 45초 후 SIGKILL.
- **무중단 롤링.** `maxUnavailable: 0`, `maxSurge: 1` → replicas 1이어도 새 파드가 Ready 된 뒤에 기존 파드를 내린다.
- **보안 기본값.** 비root(uid 10001), 읽기 전용 루트 파일시스템(+ `/tmp` emptyDir), capability 전부 제거.

GitOps 함정 메모:
- **sync-wave는 Application 사이에서 "준비될 때까지" 기다려 주지 않는다** (Argo CD는 기본적으로 Application의 health를 판정하지 않음). 그래서 metallb-config에 `retry` + `SkipDryRunOnMissingResource`를 넣어, MetalLB CRD와 웹훅이 뜰 때까지 재시도하게 했다.
- MetalLB가 실행 중에 CRD의 caBundle을 채워 넣어서 OutOfSync로 보인다 → `ignoreDifferences`로 무시한다.
- Argo CD는 기본 3분마다 Git을 확인한다. 바로 반영하려면 UI에서 Refresh를 누르거나 `kubectl -n argocd annotate application <앱> argocd.argoproj.io/refresh=hard --overwrite`를 실행한다.

### Redis (2026-11-22, 7주 차 분산 락)

- `deploy/platform/redis/redis.yaml`: `redis:8.2.11` 1대, Service `redis:6379` (ClusterIP). core는 `CORE_REDIS_ADDRESS=redis://redis:6379`
- **StatefulSet인 이유:** Deployment 롤링은 새 파드를 먼저 띄워 잠깐 Redis가 두 대가 된다 → 락 장부가 둘 → 같은 락을 둘이 잡을 수 있다. StatefulSet(replicas 1)은 내린 뒤 띄운다
- **저장 없음**(`--save ""`, `--appendonly no`): 락은 수 초짜리. 재시작 순간 락이 뚫릴 수 있는 건 DB `@Version` 안전망이 잡는다
- `noeviction`: 메모리가 차도 키를 지우지 않고 쓰기를 거절 (락 키가 몰래 지워지면 락이 뚫린다)
- 인증 없음 (클러스터 내부 전용, 학습 타협). NetworkPolicy 백로그에서 core만 접근하게 막는다
- 운영형(오퍼레이터·Sentinel·복제)은 17주 차 "Redis 페일오버" 실험 때. AWS로 치면 ElastiCache
- 확인: `kubectl -n bank exec redis-0 -- redis-cli ping`, 락 키 보기 `redis-cli --scan --pattern 'lock:*'`

### 관측성 기본 스택 (2026-11-18, 아직 미적용)

10주 차에서 **기본 스택만** 앞당겼다. 노드 확장·호스트 다운·k6 기준선을 그래프로 보면서 하려는 것이고, 같은 대시보드를 일찍부터 봐야 평소 모양이 눈에 익는다. Loki·Tempo·Alertmanager·SLO·서비스별 RED 대시보드 설계는 원래 주차에 한다.

| Argo CD 앱 | 내용 | wave |
| --- | --- | --- |
| `kube-prometheus-stack` | 차트 93.4.0 (operator v0.95.2), 네임스페이스 `monitoring`, 릴리스 `kps` | 0 |
| `grafana-dashboards` | `deploy/grafana/` (kustomize: JSON → ConfigMap `grafana_dashboard=1`) | 1 |
| `monitoring-targets` | `deploy/platform/monitoring/` Kafka·CloudNativePG PodMonitor (`bank`) | 1 |
| 서비스 4개 | 공통 차트가 서비스마다 PodMonitor 생성 (`metrics.enabled`) | 2 |

결정과 이유:
- **수집 대상은 선언으로.** prometheus-operator가 PodMonitor·ServiceMonitor를 보고 수집 설정을 만든다. `*SelectorNilUsesHelmValues: false`로 릴리스 라벨 없는 모니터도 전부 줍는다 (기본값이면 이 Helm 릴리스가 만든 것만 수집).
- **k3s에 맞춰 끈 것:** controller-manager·scheduler·kube-proxy·etcd 수집. k3s는 이것들을 파드가 아니라 k3s 프로세스 하나로 돌려서, 켜 두면 영원히 Down + 알림 규칙이 계속 울린다. **etcd는 내장 etcd 전환(노드 확장) 뒤** `--etcd-expose-metrics` + `kubeEtcd.endpoints`로 다시 켠다.
- **Alertmanager 끔** (11주 차). 기본 알림 규칙은 평가되므로 Prometheus UI의 Alerts 탭에서는 보인다.
- **operator 웹훅 끔:** Helm 훅 Job과 인증서 Secret이 Argo CD에서 매 동기화마다 다시 돌거나 OutOfSync로 남기 쉽다. 잃는 것은 PrometheusRule 문법 사전 검사 하나.
- **수집 간격 15초**(기본 30초): k6 실험이 몇 분짜리라. 대시보드에서는 `rate(...[1m])` 이상을 쓴다.
- **보존:** 7일 또는 8GB 중 먼저 닿는 쪽, PVC 10Gi(local-path). local-path라 Prometheus가 있는 노드가 죽으면 그동안 지표를 못 본다 (17주 차 호스트 다운 실험 때 "관측 도구도 같이 죽는" 장면이 될 수 있음 → 그때 재검토).
- **Grafana는 영속 볼륨 없음.** 대시보드는 Git(`deploy/grafana/`)이 기준. 화면에서 고친 건 재시작하면 사라지므로 Export → JSON → 커밋.
- **Kafka:** `metricsConfig: strimziMetricsReporter`(JMX Exporter 대신 Strimzi 자체 플러그인, 설정 한 줄) + **Kafka Exporter**(컨슈머 랙. 브로커 지표에는 랙이 없다).
- **CloudNativePG:** `Cluster.spec.monitoring.enablePodMonitor`는 1.30 CRD에서 deprecated → PodMonitor 하나로 모든 DB 인스턴스(`cnpg.io/podRole: instance`)를 수집.

**서비스 지표와 관리 포트 분리** (pitfalls 21):
- 서비스 4개에 `micrometer-registry-prometheus`(2026-11-18 승인). actuator는 **관리 포트 8081**로 옮겼다. 8080에 두면 gateway Ingress로 `/actuator/prometheus`가 인터넷에 열린다.
- 8081은 Service에 넣지 않는다 → Ingress로 나갈 길이 없고, Prometheus는 **PodMonitor**로 파드를 직접 긁는다 (ServiceMonitor는 Service 포트가 필요해서 PodMonitor를 골랐다).
- **프로브는 앱 포트의 `/livez`·`/readyz`** (`add-additional-paths: true`). 관리 포트로 프로브를 받으면 앱 포트가 고장 나도 Ready로 보인다.
- 포트 번호의 기준은 차트(`managementPort`) → 환경변수 `MANAGEMENT_SERVER_PORT`로 앱 설정을 덮어쓴다.
- `http.server.requests` 히스토그램을 켰다 → Prometheus에서 `histogram_quantile`로 p99. 앱 안에서 계산하는 백분위(`percentiles`)는 파드 여러 개를 합칠 수 없어서 쓰지 않는다.
- 모든 지표에 `application` 라벨 (`management.metrics.tags.application`). JVM 대시보드가 이 라벨로 고른다.
- **첫 배포 때 주의:** 차트(프로브 경로 변경)가 먼저 반영되고 새 이미지 태그가 몇 분 뒤에 반영되는 사이, 옛 이미지 + 새 프로브(`/livez` 404) 조합의 새 파드가 Ready가 안 된다. `maxUnavailable: 0`이라 기존 파드가 계속 서비스하므로 장애는 아니고, CI가 태그를 커밋하면 풀린다 (pitfalls 18 expand → contract의 작은 사례).

Grafana 관리자 계정 (Git 밖, 클러스터에서 한 번):
```bash
kubectl create namespace monitoring --dry-run=client -o yaml | kubectl apply -f -
kubectl -n monitoring create secret generic grafana-admin \
  --from-literal=admin-user=admin --from-literal=admin-password="$(openssl rand -base64 18)"
kubectl -n monitoring get secret grafana-admin -o jsonpath='{.data.admin-password}' | base64 -d; echo
```
차트에 맡기면 무작위 비밀번호를 만드는데, Argo CD가 동기화마다 템플릿을 다시 그려 비밀번호가 계속 바뀐다. 그래서 미리 만든 Secret을 쓴다.

메모리 예산 (요청 / 한도): Prometheus 768Mi / 1.5Gi, Grafana 192Mi / 384Mi, operator 128Mi / 256Mi, kube-state-metrics 64Mi / 128Mi, node-exporter 노드당 32Mi / 64Mi, Kafka Exporter 64Mi / 128Mi → 요청 합계 약 1.4GB. 적용 후 실측으로 갱신한다.

적용 후 확인 (노드 올린 뒤):
1. Secret 생성 → push → Argo CD에서 `kube-prometheus-stack`·`grafana-dashboards`·`monitoring-targets` Healthy
2. `kubectl -n monitoring port-forward svc/kps-prometheus 9090` → Status → Targets: apiserver·kubelet·coredns·node-exporter·kube-state-metrics·Kafka·Kafka Exporter·CNPG 3개·서비스 4개 모두 UP
3. `http://grafana.172.30.1.240.nip.io` → 노드·파드 기본 대시보드, Strimzi Kafka·Kafka Exporter(notification 컨슈머 그룹 랙), CloudNativePG, JVM (Micrometer)
4. 송금 몇 건 → `histogram_quantile(0.99, sum by (le, uri) (rate(http_server_requests_seconds_bucket{application="transfer"}[1m])))`

### 알려진 한계
- bpg 프로바이더는 스니펫 업로드와 이미지 디스크 임포트를 SSH로 처리한다. 그래서 이 부분만 Proxmox root SSH(키 인증)를 쓴다. 개선 방향: sudo 권한이 있는 일반 SSH 사용자.
- tfstate는 로컬 파일이다 (혼자 쓰는 홈랩이라 충분). 팀이라면 원격 state와 잠금(S3+DynamoDB, Terraform Cloud 등)이 필요하다.
- k3s 토큰과 Proxmox 토큰이 tfstate·tfvars에 평문으로 남는다 → gitignore로 커밋을 막는다.

## 4. 학습 노트

- [클라우드 이미지와 cloud-init](notes/cloud-image-and-cloud-init.md): 누가 무엇을 실행하는지, 인프라가 뜨는 순서, 디버깅
- [쿠버네티스 서비스 네트워킹과 MetalLB](notes/k8s-service-networking.md): 파드 IP·ClusterIP·NodePort·LoadBalancer, ARP, MetalLB L2/BGP, Ingress와의 관계
- [GitOps와 Argo CD는 어떻게 배포하나](notes/gitops-argocd.md): Git과 클러스터 비교·적용, 롤링 업데이트, 이미지 배포 흐름(CI + CD)

### 작업 절차 (runbooks)

- [두 번째 Proxmox 호스트 설치](runbooks/proxmox2-install.md): 개발자가 USB로 설치하는 절차, `172.30.1.201` (2026-11-17 설치 완료)
- [컨트롤 플레인 중재자 노트북 설치](runbooks/witness-laptop-install.md): Ubuntu Server, `bank-cp3` / `172.30.1.202` (2026-11-18 설치 완료)

## 5. 결정 기록

큰 결정은 `docs/adr/`에 ADR로 남기고, 여기에는 한 줄 요약과 링크만 둔다.

- (아직 없음)

## 6. 접속 정보 / 엔드포인트

비밀번호, 토큰, kubeconfig, tfstate는 **절대 커밋하지 않는다.** 여기에는 위치와 방법만 적는다.

- Proxmox UI: `https://172.30.1.200:8006`
- Proxmox SSH: `ssh root@172.30.1.200`
- kube-apiserver: `https://172.30.1.230:6443` (kubeconfig: `~/.kube/bank-msa-lab.yaml`, 레포 밖)
- Argo CD UI: `http://argocd.172.30.1.240.nip.io` (admin / 비밀번호: `kubectl -n argocd get secret argocd-initial-admin-secret -o jsonpath='{.data.password}' | base64 -d`)
- ~~hello 테스트 앱~~: 2026-11-19 제거 (1주 차 GitOps 확인용). 헤더·네트워크 실험(X-Forwarded-For 등)에 whoami가 필요하면 `kubectl run`으로 잠깐 띄운다
- core: `http://core.172.30.1.240.nip.io/actuator/health` (3주 차 gateway가 생기면 닫는다)
- Grafana: `http://grafana.172.30.1.240.nip.io` (admin / 비밀번호: Secret `monitoring/grafana-admin`, 위 "관측성 기본 스택")
- Prometheus UI: Ingress 없음 (인증이 없어서). `kubectl -n monitoring port-forward svc/kps-prometheus 9090`

## 7. 트러블슈팅 로그

- **2026-11-23 / 밖(Tailscale)에서 proxmox가 "꺼짐"으로 보임, 클러스터 접속 불가** / 호스트는 켜져 있었고 **랜선이 빠져** 7시간 가까이 고립. 커널 로그: 13:38 부팅 때 이미 `Downshift occurred from negotiated speed 1Gbps to actual speed 100Mbps, check cabling!`, 16:01 `enp5s0: Link is Down` 뒤 복구 없음. 11/18 10Mbps·11/19 100Mb/s와 같은 계열 / 다시 꽂아 1Gbps 복구. **같은 경고가 또 나오면 랜선 교체.** 교훈: ① 밖에서는 "꺼짐"과 "네트워크 끊김"이 똑같이 보인다 → 집에 가서 `last -x`(정상 종료 기록 유무)와 `journalctl -b -1 -k | grep enp5s0`(링크 기록)부터 ② 커널의 `check cabling!` 경고는 장애 예고였다. 노드 NotReady 알림(11주 차)이 있었으면 16:01에 알았다 ③ 같은 날 22:46 `Power key pressed short`는 개발자가 전원 LED 선을 다시 꽂다 누른 것 (원인으로 오해할 뻔함)
- **2026-11-19 / proxmox2가 Wake-on-LAN으로 안 켜지고, 전원 버튼으로 켜도 네트워크에 안 붙음** / ① ASUS TUF GAMING B760M-PLUS는 키보드가 없으면 "F1을 누르세요"에서 부팅이 멈춘다 (화면 없이 보면 네트워크 장애처럼 보임) ② `Power On By PCI-E`가 기본값으로 꺼져 있어, OS의 `Wake-on: g`만으로는 깨어나지 않음 / BIOS: `Boot → Wait For 'F1' If Error` Disabled, `Advanced → APM → ErP Ready` Disabled · `Power On By PCI-E` Enabled → 끄고 WOL로 켜기 확인. 교훈: OS 설정만 보고 "WOL 됨"이라 하지 말고 **꺼진 상태에서 실제로 켜 보는 것**을 완료 조건으로. 덤: 같은 날 proxmox 링크가 100Mb/s로 붙음 (11/18 10Mbps와 같은 계열) → 랜선 교체 후보
- **2026-11-17 / proxmox2에서 `apt update`가 "Temporary failure resolving"** / 인터넷(`ping 1.1.1.1`)은 되는데 공유기 DNS(`172.30.1.254:53`)가 이 호스트의 질의에 응답하지 않음 (원시 DNS 패킷으로 확인: `.254` 시간 초과, `1.1.1.1` 정상). 기존 VM들은 Terraform이 `8.8.8.8`을 두 번째 DNS로 줘서 드러나지 않았던 것 / `/etc/resolv.conf`를 `1.1.1.1` → `8.8.8.8` → `.254` 순으로. 공유기를 앞에 두면 질의마다 타임아웃(수 초)을 기다린 뒤 넘어가서 느려진다
- **2026-11-17 / Terraform 토큰 스크립트가 PVE 9에서 실패할 뻔** / PVE 9에서 `VM.Monitor` 권한이 없어지고 `VM.GuestAgent.*`로 쪼개짐. 그리고 스크립트가 `terraform.tfvars`를 통째로 덮어써서 두 번째 호스트용으로 돌리면 기존 토큰이 지워지는 구조였음 / 버전 감지해 권한 분기, tfvars는 변수 한 줄만 교체 (`TFVAR=pve2_api_token`)

`날짜 / 증상 / 원인 / 해결` 형식으로 쌓는다. 이 기록도 면접 답변 소재가 된다.

- **2026-11-11 / MetalLB 설치 후 노드마다 `frr-k8s` 파드가 뜸 (Init 상태로 오래 걸림)** / MetalLB 0.16 차트는 BGP용 FRR-K8s 백엔드를 기본으로 켠다 / L2 모드만 쓰므로 `frrk8s.enabled: false` → Git push → Argo CD가 반영. 노드당 파드 2개에서 1개(speaker)로 줄었다.
- **2026-11-11 / hello 응답의 `X-Forwarded-For`가 고객(맥북) IP가 아니라 `10.42.3.1`** / NodePort·LB 경로의 SNAT 때문에 원래 IP가 사라짐 (`notes/k8s-service-networking.md` 참고) / 아직 해결하지 않음. 필요해지면 Traefik 서비스에 `externalTrafficPolicy: Local`을 검토한다.
- **2026-11-19 / Argo CD Application 파일을 지워도 앱의 리소스는 남는다 (hello 제거 중 발견)** / Application에 `resources-finalizer.argocd.argoproj.io`가 없으면 root 앱의 prune은 Application 객체만 지우고, 그 앱이 만든 Deployment·Service·Ingress는 주인 없이 남는다 / 두 단계로: ① finalizer를 붙여 push → 반영 확인 ② 파일 삭제 push → 리소스까지 정리. `CreateNamespace=true`로 생긴 네임스페이스는 앱 리소스가 아니라 남으므로 따로 지운다. 앞으로 만드는 Application에는 처음부터 finalizer를 넣는 걸 검토
