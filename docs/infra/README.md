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

합계 메모리 28GB. 호스트에 약 4GB가 남는다. 메모리는 벌루닝 없이 고정 할당한다.

### IP 계획

| 용도 | 범위 |
| --- | --- |
| 공유기 DHCP (사용 금지) | .1~100, .128~148 |
| Proxmox 호스트 | .200 |
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

# 3) Argo CD 설치 + root 앱 등록 (최초 1회). 이후 MetalLB·hello 등은 Argo CD가 Git을 보고 설치
./infra/bootstrap/install-argocd.sh

# 전부 지우고 다시 만들기
terraform destroy && terraform apply   # 이후 2), 3) 다시 실행
```

### 전원 관리 (전기료 절약: 실험할 때만 켠다)

| 동작 | 스크립트 | Raycast |
| --- | --- | --- |
| 켜기: Wake-on-LAN → VM 230→231~233 시작 → 노드 Ready 대기 | `infra/scripts/cluster-up.sh` | Bank Lab Up 🟢 |
| 끄기: 워커 → 컨트롤 플레인 정상 종료 → 호스트 `poweroff` | `infra/scripts/cluster-down.sh` | Bank Lab Down 🔴 (확인 창 있음) |
| 상태: 호스트 / VM / 노드 / Argo CD 앱 | `infra/scripts/cluster-status.sh` | Bank Lab Status 📊 |

- Raycast 등록: Raycast 설정 → Extensions → Script Commands → Add Directories → `infra/scripts/raycast` 선택
- 공통 설정: `infra/scripts/lab.env` (IP, MAC, VMID, PATH)
- Wake-on-LAN: Proxmox `enp5s0` (MAC `22:02:4d:07:2d:06`) 지원 모드 `pumbg`. 기본값이 `d`(꺼짐)라 `infra/bootstrap/enable-wol.sh`로 `wol g`를 켜고, systemd 서비스 `wol-enp5s0`로 부팅마다 다시 켜지게 했다.
  - 매직 패킷은 브로드캐스트라 **맥북이 집 네트워크에 있어야** 한다.
  - 2026-11-11 실제 테스트: Bank Lab Down으로 종료 → Bank Lab Up으로 원격 부팅 성공. **BIOS 설정 변경 없이 동작했다.** 재부팅 후 노드 4대 Ready, Argo CD 앱 4개 Synced/Healthy, hello v2 응답까지 자동으로 복구됐다.
  - 종료는 VM을 하나씩 정상 종료하느라 1~2분 걸린다 (바로 꺼지지 않는다고 당황하지 말 것).
- **TODO (나중에): 집 밖에서 켜고 끄기.** Proxmox가 꺼져 있으면 매직 패킷을 대신 보내 줄 "항상 켜진 집 안의 무언가"가 필요하다. 후보: 공유기의 원격 WOL 기능 / 라즈베리파이 같은 저전력 기기 + Tailscale(VPN). Proxmox나 SSH 포트를 인터넷에 직접 열지는 않는다 (보안).
- VM은 `on_boot = false`라서 호스트만 켜면 VM은 꺼진 채로 있다 → cluster-up.sh가 켠다.

### 배포 구조 (GitOps)

```
deploy/
├── argocd/
│   ├── bootstrap/values.yaml   # Argo CD 자체 Helm 설정 (최초 설치용)
│   ├── root.yaml               # App of Apps 뿌리 (최초 1회 kubectl apply)
│   └── apps/                   # 여기에 Application 파일을 추가하면 Argo CD가 설치
│       ├── metallb.yaml          (wave 0, 공식 Helm 차트)
│       ├── metallb-config.yaml   (wave 1, IP 풀)
│       ├── hello.yaml            (wave 2)
│       └── core.yaml             (wave 2, 공통 차트 + core values → 네임스페이스 bank)
├── platform/metallb-config/    # IPAddressPool, L2Advertisement
├── docker/spring-boot.Dockerfile  # 모든 Spring 서비스 공통 Dockerfile
├── helm/spring-service/        # 모든 Spring 서비스 공통 차트 (Deployment, Service, Ingress)
└── apps/
    ├── hello/                  # Deployment, Service, Ingress (평문 매니페스트)
    └── core/values.yaml        # core 배포 설정. image.tag는 CI가 갱신
```

서비스 배포 흐름 (`.github/workflows/services.yaml`):
```
services/ 등 push → 테스트 → amd64 이미지 → ghcr.io/utopiandreams/<서비스>:<커밋해시>
  → deploy/apps/<서비스>/values.yaml의 tag 커밋 (github-actions[bot]) → Argo CD 반영 → 롤링 업데이트
```

새 서비스 추가: ① 워크플로 `matrix.service`에 이름 추가 ② `deploy/apps/<서비스>/values.yaml` ③ `deploy/argocd/apps/<서비스>.yaml` (core.yaml 복사). 첫 push 후 GHCR 패키지를 public으로 바꾼다.

공통 차트의 실무 포인트:
- **CPU limit 없음, 메모리 limit만.** JVM 기동 때 CPU 스로틀링 → startupProbe 실패 → 재시작 루프를 피한다. 힙은 메모리 limit의 75%.
- **프로브 3종.** startup(기동 끝날 때까지 나머지 보류) / liveness(실패 시 재시작 → 재시작으로 낫는 것만) / readiness(실패 시 Service에서 제외).
- **종료 순서.** preStop sleep 5초(엔드포인트 제거가 Traefik까지 퍼질 시간) → SIGTERM → Spring graceful shutdown → `terminationGracePeriodSeconds` 45초 후 SIGKILL.
- **무중단 롤링.** `maxUnavailable: 0`, `maxSurge: 1` → replicas 1이어도 새 파드가 Ready 된 뒤에 기존 파드를 내린다.
- **보안 기본값.** 비root(uid 10001), 읽기 전용 루트 파일시스템(+ `/tmp` emptyDir), capability 전부 제거.

GitOps 함정 메모:
- **sync-wave는 Application 사이에서 "준비될 때까지" 기다려 주지 않는다** (Argo CD는 기본적으로 Application의 health를 판정하지 않음). 그래서 metallb-config에 `retry` + `SkipDryRunOnMissingResource`를 넣어, MetalLB CRD와 웹훅이 뜰 때까지 재시도하게 했다.
- MetalLB가 실행 중에 CRD의 caBundle을 채워 넣어서 OutOfSync로 보인다 → `ignoreDifferences`로 무시한다.
- Argo CD는 기본 3분마다 Git을 확인한다. 바로 반영하려면 UI에서 Refresh를 누르거나 `kubectl -n argocd annotate application <앱> argocd.argoproj.io/refresh=hard --overwrite`를 실행한다.

### 알려진 한계
- bpg 프로바이더는 스니펫 업로드와 이미지 디스크 임포트를 SSH로 처리한다. 그래서 이 부분만 Proxmox root SSH(키 인증)를 쓴다. 개선 방향: sudo 권한이 있는 일반 SSH 사용자.
- tfstate는 로컬 파일이다 (혼자 쓰는 홈랩이라 충분). 팀이라면 원격 state와 잠금(S3+DynamoDB, Terraform Cloud 등)이 필요하다.
- k3s 토큰과 Proxmox 토큰이 tfstate·tfvars에 평문으로 남는다 → gitignore로 커밋을 막는다.

## 4. 학습 노트

- [클라우드 이미지와 cloud-init](notes/cloud-image-and-cloud-init.md): 누가 무엇을 실행하는지, 인프라가 뜨는 순서, 디버깅
- [쿠버네티스 서비스 네트워킹과 MetalLB](notes/k8s-service-networking.md): 파드 IP·ClusterIP·NodePort·LoadBalancer, ARP, MetalLB L2/BGP, Ingress와의 관계
- [GitOps와 Argo CD는 어떻게 배포하나](notes/gitops-argocd.md): Git과 클러스터 비교·적용, 롤링 업데이트, 이미지 배포 흐름(CI + CD)

## 5. 결정 기록

큰 결정은 `docs/adr/`에 ADR로 남기고, 여기에는 한 줄 요약과 링크만 둔다.

- (아직 없음)

## 6. 접속 정보 / 엔드포인트

비밀번호, 토큰, kubeconfig, tfstate는 **절대 커밋하지 않는다.** 여기에는 위치와 방법만 적는다.

- Proxmox UI: `https://172.30.1.200:8006`
- Proxmox SSH: `ssh root@172.30.1.200`
- kube-apiserver: `https://172.30.1.230:6443` (kubeconfig: `~/.kube/bank-msa-lab.yaml`, 레포 밖)
- Argo CD UI: `http://argocd.172.30.1.240.nip.io` (admin / 비밀번호: `kubectl -n argocd get secret argocd-initial-admin-secret -o jsonpath='{.data.password}' | base64 -d`)
- hello 테스트 앱: `http://hello.172.30.1.240.nip.io`
- core: `http://core.172.30.1.240.nip.io/actuator/health` (3주 차 gateway가 생기면 닫는다)

## 7. 트러블슈팅 로그

`날짜 / 증상 / 원인 / 해결` 형식으로 쌓는다. 이 기록도 면접 답변 소재가 된다.

- **2026-11-11 / MetalLB 설치 후 노드마다 `frr-k8s` 파드가 뜸 (Init 상태로 오래 걸림)** / MetalLB 0.16 차트는 BGP용 FRR-K8s 백엔드를 기본으로 켠다 / L2 모드만 쓰므로 `frrk8s.enabled: false` → Git push → Argo CD가 반영. 노드당 파드 2개에서 1개(speaker)로 줄었다.
- **2026-11-11 / hello 응답의 `X-Forwarded-For`가 고객(맥북) IP가 아니라 `10.42.3.1`** / NodePort·LB 경로의 SNAT 때문에 원래 IP가 사라짐 (`notes/k8s-service-networking.md` 참고) / 아직 해결하지 않음. 필요해지면 Traefik 서비스에 `externalTrafficPolicy: Local`을 검토한다.
