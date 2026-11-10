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
- [ ] MetalLB: Traefik `LoadBalancer` 서비스가 `<pending>` 상태 (servicelb를 끈 결과로, 의도한 상태)
- [ ] Argo CD + hello 앱 GitOps

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
| GitOps | Argo CD | 확정 |
| Git 저장소 | `git@github.com:utopiandreams/bank-msa-lab.git` | 확정 |

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

# 전부 지우고 다시 만들기
terraform destroy && terraform apply
```

### 알려진 한계
- bpg 프로바이더는 스니펫 업로드와 이미지 디스크 임포트를 SSH로 처리한다. 그래서 이 부분만 Proxmox root SSH(키 인증)를 쓴다. 개선 방향: sudo 권한이 있는 일반 SSH 사용자.
- tfstate는 로컬 파일이다 (혼자 쓰는 홈랩이라 충분). 팀이라면 원격 state와 잠금(S3+DynamoDB, Terraform Cloud 등)이 필요하다.
- k3s 토큰과 Proxmox 토큰이 tfstate·tfvars에 평문으로 남는다 → gitignore로 커밋을 막는다.

## 4. 학습 노트

- [클라우드 이미지와 cloud-init](notes/cloud-image-and-cloud-init.md): 누가 무엇을 실행하는지, 인프라가 뜨는 순서, 디버깅

## 5. 결정 기록

큰 결정은 `docs/adr/`에 ADR로 남기고, 여기에는 한 줄 요약과 링크만 둔다.

- (아직 없음)

## 6. 접속 정보 / 엔드포인트

비밀번호, 토큰, kubeconfig, tfstate는 **절대 커밋하지 않는다.** 여기에는 위치와 방법만 적는다.

- Proxmox UI: `https://172.30.1.200:8006`
- Proxmox SSH: `ssh root@172.30.1.200`
- kube-apiserver: `https://172.30.1.230:6443` (kubeconfig: `~/.kube/bank-msa-lab.yaml`, 레포 밖)
- Argo CD UI: TBD

## 7. 트러블슈팅 로그

`날짜 / 증상 / 원인 / 해결` 형식으로 쌓는다. 이 기록도 면접 답변 소재가 된다.

- (아직 없음)
