# 클라우드 이미지와 cloud-init

> 한 줄 요약: **OS가 이미 설치된 디스크(클라우드 이미지)를 붙여 부팅하면, 그 안에 내장된 cloud-init이 첫 부팅 때 가상 CD-ROM에서 설정을 읽어 계정·IP·패키지·k3s 설치를 스스로 한다.**
> AWS의 AMI + EC2 user-data와 같은 개념이다.

## 1. 클라우드 이미지

| | ISO 설치 이미지 | 클라우드 이미지 |
| --- | --- | --- |
| 정체 | 설치 프로그램 | **OS 설치가 끝난 디스크** |
| 사용 | 부팅 후 화면에서 설치 진행 | 디스크로 붙이고 켜면 바로 부팅 |
| 크기 | 약 2.6GB | 약 600MB (최소 구성) |
| 계정·IP·호스트명 | 설치하면서 입력 | 비어 있음 → 첫 부팅 때 cloud-init이 채움 |

- 이 프로젝트에서 쓰는 이미지: `https://cloud-images.ubuntu.com/noble/current/noble-server-cloudimg-amd64.img` (Ubuntu 24.04)
- 클라우드 이미지에는 cloud-init이 미리 설치돼 있다. AWS AMI, GCP 이미지도 같은 형태다.

## 2. cloud-init

### 누가 실행하나
**VM 안의 OS가 스스로 실행한다.** Proxmox도 Terraform도 실행하지 않는다.

```
Terraform    : "VM 만들고 cloud-init 드라이브에 이 설정 넣어"     ← 지시
   ↓ API
Proxmox      : 설정 파일을 4MB ISO(라벨 cidata)로 만들어 VM에 꽂음  ← 배달
   ↓ 가상 CD-ROM (/dev/sr0)
VM 안 Ubuntu : 부팅 → systemd가 cloud-init 실행 → 설정대로 수행    ← 실행
```

- cloud-init은 apt로 설치된 일반 프로그램이다 (확인 시 `cloud-init 26.1`).
- 부팅할 때 systemd 서비스 4단계로 돈다:
  1. `cloud-init-local`: 설정(datasource)을 찾고 네트워크 설정
  2. `cloud-init`: 호스트명, 계정 등
  3. `cloud-config`: 패키지 설치 등
  4. `cloud-final`: `runcmd` 실행 (여기서 k3s 설치)
- **첫 부팅 때만** 적용된다 (instance-id 기준). 재부팅해도 다시 실행되지 않는다.
- Proxmox와 Terraform은 VM 내부 작업(k3s 설치)이 끝났는지 모른다. 끝났는지는 VM 안에서 `cloud-init status --wait`로 확인한다.

### 어떤 기술의 일부인가
어디에도 속하지 않은 **독립 오픈소스**(Canonical이 시작)이고, 클라우드 VM 초기화의 사실상 업계 표준이다. cloud-init은 어디서나 같고, **설정을 받는 곳(datasource)만 플랫폼마다 다르다.**

| 플랫폼 | datasource | 설정이 오는 곳 |
| --- | --- | --- |
| Proxmox (이 프로젝트) | NoCloud | 가상 CD-ROM (`cidata` 라벨, user-data / meta-data / network-config) |
| AWS EC2 | Ec2 | 메타데이터 서버 `http://169.254.169.254` |
| GCP / Azure | GCE / Azure | 각자의 메타데이터 서버 |
| OpenStack | ConfigDrive / OpenStack | 설정 디스크 또는 메타데이터 API |

→ 이 프로젝트의 user-data(`infra/terraform/templates/*.tftpl`)는 EC2 user-data에 거의 그대로 쓸 수 있다.

## 3. 이 프로젝트에서 인프라가 뜨는 순서

```
① create-terraform-token.sh ──SSH──▶ Proxmox에 terraform@pve 사용자·역할·토큰 생성
② terraform apply
   ├─ 클라우드 이미지 다운로드 (API, 약 47초)
   ├─ cloud-init user-data 스니펫 4개 업로드 (SSH)
   └─ VM 4대 생성 (API): 이미지를 디스크로 복사·확장, CPU·메모리·IP, cloud-init 드라이브, 전원 ON
        └─ [각 VM 안] cloud-init: 호스트명 → 계정 → 패키지 → qemu-guest-agent → k3s 설치
             · bank-cp: k3s server
             · bank-w1~3: k3s 토큰으로 172.30.1.230:6443에 합류
③ cloud-init 완료 대기 (cloud-init status --wait)
④ fetch-kubeconfig.sh: bank-cp의 k3s.yaml → ~/.kube/bank-msa-lab.yaml
⑤ kubectl get nodes → 4대 Ready
```

### 두 가지 열쇠
- **Proxmox API 토큰** (`terraform@pve!provider`): Terraform이 Proxmox에 지시할 때 쓴다. `terraform.tfvars`에 있다.
- **k3s 토큰**: Terraform이 랜덤으로 생성해 4대의 cloud-init에 똑같이 넣는다. 워커가 클러스터 멤버임을 증명한다. tfstate에 있다.

### 역할 분담
- **Terraform:** VM 바깥(하드웨어). 대수, CPU, 메모리, 디스크, IP.
- **cloud-init:** VM 안(OS 설정). 계정, 패키지, k3s.

→ `terraform destroy && terraform apply`로 전체를 언제든 똑같이 재현할 수 있다 (Pets vs Cattle).

## 4. 관리형 k8s(EKS 등)와의 관계

관리형 k8s에서도 노드는 같은 원리로 만들어진다. 사용자에게 보이지 않을 뿐이다.

```
EKS 최적화 AMI (클라우드 이미지, kubelet 등이 미리 설치됨)
  + user-data (클러스터 엔드포인트, CA, 합류 설정)
  → EC2 부팅 → cloud-init → 부트스트랩 도구 (AL2: bootstrap.sh / AL2023: nodeadm) → 컨트롤 플레인에 합류
```

| | 이 프로젝트 | EKS |
| --- | --- | --- |
| 이미지 | Ubuntu 클라우드 이미지 | EKS 최적화 AMI |
| k8s 설치 시점 | 부팅 때 (`runcmd`로 k3s 설치) | 이미지에 미리 포함 → 부팅이 빨라 오토스케일에 유리 |
| 합류 정보 | k3s 토큰 + 서버 IP | 클러스터 엔드포인트 + CA + IAM 기반 인증 |

실무에서 직접 만지는 순간:
- 노드 커스터마이징: EKS Launch Template / Karpenter `EC2NodeClass`의 `userData` (sysctl, ulimit, 사내 인증서 등)
- Cluster API: kubeadm 명령이 담긴 cloud-init user-data를 자동 생성한다. 이 프로젝트에서 Terraform 템플릿으로 한 일을 자동화한 것
- 노드가 클러스터에 안 붙을 때: 노드에 접속해 `/var/log/cloud-init-output.log`부터 확인

cloud-init을 쓰지 않는 대안 (불변 OS 계열):
- Bottlerocket: 자체 에이전트가 TOML user-data를 읽음
- Flatcar, Fedora CoreOS: Ignition
- Talos: 자체 API로만 설정

## 5. 디버깅

| 확인할 것 | 명령 / 파일 |
| --- | --- |
| 끝났는지, 에러가 있었는지 | `cloud-init status --long` |
| runcmd 출력 (k3s 설치 로그) | `/var/log/cloud-init-output.log` |
| cloud-init 상세 로그 (모듈별 실행 시각) | `/var/log/cloud-init.log` |
| 설정을 어디서 읽었나 | `sudo cloud-init query platform` → `nocloud` |
| 실제로 받은 user-data | `/var/lib/cloud/instance/user-data.txt` (**k3s 토큰 포함, 외부 공유 금지**) |

## 6. 면접에서 쓸 수 있는 문장
- "클라우드 이미지와 cloud-init으로 VM을 초기화하고, Terraform으로 Proxmox VM을 코드로 관리했습니다. AWS의 AMI + user-data + Terraform과 같은 구조입니다."
- "빈 하이퍼바이저에서 `terraform apply` 한 번, 약 2분이면 k8s 클러스터 4노드가 재현됩니다."
- "온프레미스에서 EKS 노드 그룹이 내부적으로 하는 일(이미지 + user-data로 노드 부트스트랩 → 클러스터 합류)을 직접 재현했습니다."
