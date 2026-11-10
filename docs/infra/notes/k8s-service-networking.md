# 쿠버네티스 서비스 네트워킹과 MetalLB

> 핵심 질문: **클러스터 밖(맥북)에서 파드까지 패킷이 어떻게 가는가?**
> 2026-11-11, bank-msa-lab 클러스터에서 직접 실험한 결과를 바탕으로 정리.

## 먼저 읽기: 쉬운 설명

### 트래픽
네트워크를 오가는 데이터(요청과 응답) 전체. "트래픽이 bank-w1으로 들어간다" = "요청 데이터가 bank-w1을 거쳐 들어간다".

### ClusterIP = 회사 대표번호

| 회사 전화 | 쿠버네티스 |
| --- | --- |
| 직원 개인 내선번호 (자리 이동하면 바뀜) | 파드 IP (재시작하면 바뀜) |
| 대표번호 1588-xxxx (안 바뀜) | ClusterIP |
| 대표번호를 내선으로 연결하는 교환기 설정 | iptables 규칙 (kube-proxy가 작성) |

대표번호에는 그 번호를 가진 전화기가 없다. ClusterIP도 그 IP를 가진 장치가 없다.

**증거 (2026-11-11 bank-w1에서 확인)**
1. ClusterIP는 **Service 리소스(쿠버네티스 표준, k3s 전용 아님) 안의 한 칸**이다. `kubectl get svc traefik -n kube-system -o yaml` → `clusterIP: 10.43.212.187`. `selector`로 연결할 파드를 라벨로 찾는다.
2. 노드의 네트워크 장치(`ip addr`)에는 이 IP가 없다: `eth0 172.30.1.231`, `flannel.1 10.42.2.0`, `cni0 10.42.2.1` 뿐이다.
3. 대신 iptables(리눅스 커널 내장 패킷 검문소) 규칙에 있다 (`sudo iptables-save -t nat`):
   ```
   -A KUBE-SERVICES -d 10.43.212.187/32 -p tcp --dport 80 ... "kube-system/traefik:web cluster IP" -j KUBE-SVC-...
   -A KUBE-SEP-...  ... "kube-system/traefik:web" -j DNAT --to-destination 10.42.3.2:8000
   ```
   = "목적지가 10.43.212.187:80이면 → 10.42.3.2:8000으로 바꿔치기(DNAT)"
4. 이 규칙은 모든 노드의 **kube-proxy**가 Service를 보고 써 넣는다.

**맥북에서 안 되는 이유:** 규칙이 클러스터 노드 안에만 있다. 맥북과 공유기는 10.43이 어디 있는지 모른다.

### ClusterIP는 파드들에 요청을 나눠 준다 (실험)
whoami 파드 3개(bank-w1, w2, w3에 하나씩) + 서비스를 만들고, ClusterIP로 10번 요청한 결과: 4 / 3 / 3으로 분산됐다.

iptables 규칙의 확률 계산:
```
--probability 0.333 → 파드 A
--probability 0.5   → 파드 B   (남은 2/3 중 절반 = 1/3)
(나머지)            → 파드 C   (1/3)
```
파드 수가 바뀌면 kube-proxy가 확률표를 다시 쓴다. 죽은 파드는 규칙에서 빠진다.

**쓰임새:** 서비스 간 내부 호출. 코드에는 `http://account:8080`처럼 서비스 이름만 쓰고, CoreDNS(클러스터 내부 DNS)가 이름을 ClusterIP로 바꿔 준다. 호출하는 쪽은 파드 수나 IP 변화를 몰라도 된다.

**실무 함정:** 분배는 요청 단위가 아니라 **연결(커넥션) 단위**다. keep-alive나 gRPC처럼 연결을 재사용하면 한 파드로만 요청이 몰릴 수 있다 ("파드를 늘렸는데 하나만 바쁘다").

### Traefik = 클러스터 정문 안내 데스크

| 건물 | 쿠버네티스 |
| --- | --- |
| 방문객 "hello팀 찾아왔어요" | 요청 `http://hello.xxx.nip.io` |
| 안내 데스크 직원 | **Traefik** (Ingress Controller, 실제로 요청을 받아 전달하는 프로그램) |
| 안내표 "hello팀 → 3층" | **Ingress** (쿠버네티스 표준 리소스, 라우팅 규칙) |
| hello팀 대표번호 | hello 서비스의 ClusterIP |

- Traefik은 k3s 기본 Ingress Controller다. 다른 선택지로 NGINX, HAProxy 등이 있다.
- Ingress 규칙이 하나도 없을 때 Traefik은 404로 응답한다 (위 실험의 404).
- 필요한 이유: 서비스마다 외부 IP를 주지 않고, 정문 하나에서 주소를 보고 안내한다.
- **Spring Cloud Gateway(`gateway` 서비스)와의 차이:** Traefik은 인프라 수준의 정문으로, 주소만 보고 안내한다. gateway는 애플리케이션 수준에서 JWT 인증과 Idempotency-Key 같은 업무 규칙을 처리한다. 흐름: 브라우저 → Traefik → gateway → 각 서비스.

### Ingress도 매니페스트(YAML)다

```yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: hello
spec:
  rules:
    - host: hello.172.30.1.240.nip.io   # 이 주소로 오면
      http:
        paths:
          - path: /
            pathType: Prefix
            backend:
              service:
                name: hello             # hello 서비스(ClusterIP)로
                port:
                  number: 80
```

### Ingress vs gateway: 이 프로젝트에서의 역할 분담

| | 질문 | 판단 기준 |
| --- | --- | --- |
| Ingress (Traefik) | 어느 **시스템**으로? | 주소(Host) |
| gateway (Spring Cloud Gateway) | API 안에서 어느 **서비스**로? 인증은? | 경로(Path), JWT, Idempotency-Key |

```
                              ┌─ api.xxx      → gateway ─┬─ /accounts/**  → account
브라우저 → 172.30.1.240 → Traefik                         └─ /transfers/** → transfer ...
          (MetalLB)    (Ingress) ├─ panel.xxx   → web
                              ├─ grafana.xxx → Grafana
                              └─ argocd.xxx  → Argo CD UI
```

- Ingress 없이 gateway에 외부 IP를 바로 줄 수도 있다. 하지만 그러면 Grafana, Argo CD, web이 각자 외부 IP를 받거나, 업무와 무관한 gateway를 거쳐야 한다. HTTPS 인증서 처리도 정문 한 곳이 편하다.
- 실무에서 흔한 구조: AWS ALB(Ingress 역할) → Spring Cloud Gateway → 서비스. Kong이나 Envoy Gateway처럼 둘을 합친 제품도 있다.

### Service는 라벨로 파드를 묶는다 (Deployment와 한 쌍)
- 리소스 이름은 **Service**다. ClusterIP는 Service가 받는 IP이자 기본 타입이다 ("ClusterIP 리소스"는 따로 없다).

```yaml
kind: Deployment            # 파드 3개 생성, 이름표 app: account
spec:
  replicas: 3
  template:
    metadata:
      labels: { app: account }
---
kind: Service               # 이름표 app: account가 붙은 파드를 묶어 대표번호 하나
metadata:
  name: account             # → http://account:8080
spec:
  selector: { app: account }
  ports: [{ port: 8080 }]
```

- 파드가 늘거나 줄어도 같은 이름표면 자동으로 묶인다. Service를 고칠 필요가 없다.
- 서비스(account, transfer, ledger, gateway, ...)와 Kafka, PostgreSQL, Redis가 각자 Service를 가진다.
- 보통 Deployment와 Service가 한 쌍이고, 외부에 노출할 것만 Ingress를 추가한다.

### 전체 그림 (아파트 단지 비유)

| 비유 | 쿠버네티스 | 개수 |
| --- | --- | --- |
| 단지 정문 (단 하나, 동들 바깥) | LoadBalancer `172.30.1.240` | 1개 |
| 각 동의 1층 출입문 | NodePort `각 노드:31805` | 노드 수만큼 |
| 각 부서의 대표 내선번호 (외부에서는 안 통함) | ClusterIP | Service마다 1개 |
| 부서 직원들 | 파드 | 여러 개 |
| 로비 안내 데스크 (이것도 하나의 부서) | Traefik | 파드 + Service |

```
맥북
 ↓ ① 단지 정문 172.30.1.240             (LoadBalancer: MetalLB)
 ↓ ② 살아 있는 동 하나의 출입문 :31805    (NodePort)
 ↓ ③ 안내 데스크 대표번호                  (Traefik의 ClusterIP)
 ↓ ④ 안내 데스크 직원                      (Traefik 파드) — Ingress 규칙으로 행선지 판단
 ↓ ⑤ hello 부서 대표번호                   (hello의 ClusterIP)
 ↓ ⑥ hello 직원 중 한 명                  (hello 파드)
```
MetalLB 설치 전에는 ②~⑥만 있고 ①이 없다 → `<pending>`.

### 헷갈렸던 점
- **NodePort는 "하나의 문"이 아니다.** 노드마다 같은 번호의 문을 하나씩 연다 (노드 수만큼). 목적은 밖에서 들어올 문을 처음으로 뚫는 것이다. 하나의 문은 LoadBalancer가 만든다.
- **ClusterIP는 클러스터에 하나가 아니라 Service마다 하나씩 있다.** "Cluster"IP = 클러스터 안에서만 통하는 IP라는 뜻. Traefik도 특별하지 않은 그냥 파드 + Service라서 자기 ClusterIP가 있다.
- **LB는 노드 레벨(노드들 앞)이 맞다.** AWS ELB는 클러스터 밖 장비로, 살아 있는 노드의 NodePort로 보낸다. 온프레미스에는 그런 장비가 없어서, MetalLB가 클러스터 안에서 노드 하나에게 대표 IP를 맡기는 방식으로 그 역할을 대신한다.

### ARP와 MetalLB (쉬운 설명)
- **IP = 사람 이름, MAC = 집 주소.** 같은 네트워크 안의 배달은 집 주소(MAC)로 한다.
- **ARP = 단지 방송:** "172.30.1.230 씨 계세요? 집 주소 알려 주세요" → bank-cp: "bc:24:11:21:b5:74예요" → 맥북이 메모(ARP 테이블)
  - 증거: 맥북 `arp -an`의 `.230 → bc:24:11:21:b5:74` = Proxmox `qm config 230`의 `net0` MAC
- `.240`은 아무도 대답하지 않는 빈 이름이라 배달을 시작할 수 없다.
- **MetalLB:** 노드마다 speaker 파드가 뜬다.
  1. `<pending>` Service에 풀(.240~.250)에서 IP를 배정한다.
  2. speaker 중 대표 하나가 "`.240`은 저예요 (제 노드의 MAC)"라고 대답한다 → 패킷이 그 노드에 도착 → 이후는 iptables → Service → 파드.
  - **`.240`이라는 장치는 새로 생기지 않는다.** 기존 노드가 이름을 하나 더 맡을 뿐이다.
- **대표 노드가 죽으면:** 다른 speaker가 "이제 `.240`은 저예요"라고 먼저 방송한다 (Gratuitous ARP). 수 초 끊긴 뒤 복구된다.
- **한계:** `.240`으로 오는 트래픽은 전부 대표 노드 한 대로 들어간다 (장애 대비 O, 부하 분산 X). 데이터센터에서는 BGP 모드를 쓴다.
- flannel은 "노드끼리 파드 패킷을 대신 배달하는 지하 통로" 정도로만 이해해도 충분하다.

### MetalLB 정리
- **정체:** 쿠버네티스 Service 이름이 아니라, 클러스터에 설치하는 오픈소스 애드온(파드로 실행)이다.
  - controller (파드 1개): `<pending>` LoadBalancer Service에 IP 배정
  - speaker (노드마다 1개): 배정된 IP에 대해 ARP로 대답
- **역할:** 클라우드라면 클라우드가 처리해 주는 `type: LoadBalancer` 요청을 온프레미스에서 대신 처리한다.
- **레이어:** L2(ARP)로 알리기만 하고, **트래픽 경로에는 끼지 않는다.** 패킷은 노드에 도착해서 바로 iptables가 처리한다.
- **ARP 스푸핑과 같은 원리**다 (ARP에는 인증이 없다). 그래서 "허락받은 ARP 스푸핑"이라고도 부른다.
  - 실무 함정: 기업용 스위치의 DAI(Dynamic ARP Inspection)나 클라우드 VPC에서는 차단될 수 있다 → 클라우드에서는 MetalLB L2 대신 ELB를 쓴다. 사내망에 도입할 때는 네트워크팀과 협의가 필요하다.

### 레이어별 정리

| 순서 | 누가 | 레이어 | 판단 기준 |
| --- | --- | --- | --- |
| ① | MetalLB | L2 | "`.240`은 이 노드" (ARP로 알리기만) |
| ② | kube-proxy (iptables) | L4 | IP와 포트 → Service의 파드 |
| ③ | Traefik (Ingress) | L7 | HTTP Host → 어느 시스템 |
| ④ | gateway | L7 | HTTP 경로, JWT → 어느 서비스 |

### 주의: Service 타입 이름 `LoadBalancer` ≠ LB 장치
- Traefik Service의 **타입 이름**이 `LoadBalancer`일 뿐이다. "LB의 ClusterIP"가 아니라 "Traefik Service의 ClusterIP"다.
- LB(MetalLB)는 NodePort보다 **앞**에 선다. NodePort로 들어온 뒤의 경로에는 LB가 끼지 않는다.
- NodePort 요청은 ClusterIP 주소로 한 번 더 포워딩되는 게 아니라, ClusterIP와 **같은 처리 절차(KUBE-SVC)로 합류해 바로 파드를 고른다.**
- NodePort의 핵심: 내가 어느 노드에서 실행 중이든, 아무 노드의 그 포트로 오면 나를 찾을 수 있다.

### 파드가 없는 노드의 NodePort로 오면 어떻게 파드까지 가나
그 포트를 듣는 프로그램은 **없다** (bank-w1에서 `sudo ss -lntp | grep 31805` → 없음). 패킷이 프로그램에 닿기 전에 **커널이 가로채서** 다른 노드로 보낸다.

```
① 맥북 → 172.30.1.231:31805 (bank-w1, Traefik 없음)
② [w1 커널] iptables: 받는 사람 → 10.42.3.2:8000 (DNAT), 보낸 사람 → w1 (MASQ/SNAT)
③ [w1 커널] 라우팅 표: 10.42.3.0/24 via 10.42.3.0 dev flannel.1
④ [flannel.1 = VXLAN 터널] 패킷을 UDP 봉투에 넣어 겉봉에 172.30.1.233:8472라고 쓰고 집 네트워크로 전송
⑤ [w3] 봉투를 뜯어 10.42.3.2(Traefik 파드)에 전달
⑥ 응답은 역순으로 w3 → w1 → 맥북
```

노드별 파드 대역 (IP만 보고 파드가 어느 노드에 있는지 알 수 있다):

| 노드 | 노드 IP | 파드 대역 |
| --- | --- | --- |
| bank-cp | 172.30.1.230 | 10.42.0.0/24 |
| bank-w2 | 172.30.1.232 | 10.42.1.0/24 |
| bank-w1 | 172.30.1.231 | 10.42.2.0/24 |
| bank-w3 | 172.30.1.233 | 10.42.3.0/24 |

- `flannel.1`: `vxlan id 1 local 172.30.1.231 dev eth0 dstport 8472`. 공유기는 겉봉(노드 IP)만 보므로 10.42를 몰라도 배달된다. 이것이 "파드 네트워크는 노드끼리만 아는 세계"의 실체다.
- **보낸 사람을 바꾸는(SNAT) 이유:** 바꾸지 않으면 Traefik이 맥북에게 직접 답장한다. 맥북은 `.231`에 보냈는데 다른 곳에서 답이 오니 버린다.
- **한 줄 요약:** 노드A(.240 담당, ARP로 유인) → 노드B의 파드 → 응답은 **반드시 노드A를 거쳐야** 한다. 바꿔치기한 기록(conntrack)을 노드A만 갖고 있어서 원래대로(보낸 사람 = .240) 되돌릴 수 있기 때문이다. 그래서 보낸 사람을 노드A로 바꿔치고, 그 대가로 앱에는 손님이 전부 노드A로 보인다.
- 실측 (2026-11-11): hello 응답의 `X-Forwarded-For: 10.42.3.1` = bank-w3의 `cni0` 주소 (노드 자신). Traefik이 같은 w3에 있어서 flannel을 거치지 않았는데도 바뀌었다 → 원인은 flannel이 아니라 `externalTrafficPolicy: Cluster`(기본값)의 SNAT이다.
- 📌 **2주 차에 복습 예정** (1주 차에는 이해가 덜 됨): 아래 X-Forwarded-For 부분
- **Traefik → 다른 노드의 파드 구간은?** 바꿔치기가 없다. 실측 (Traefik on w3 → hello on w1):
  ```
  RemoteAddr:      10.42.3.2   ← 실제 연결 상대 = Traefik 파드 (노드 IP 아님)
  X-Forwarded-For: 10.42.3.1   ← Traefik이 적은 "원래 손님" 메모 (입구에서 이미 바뀐 값)
  ```
  - 파드끼리는 서로 직접 답장할 수 있어서(파드 IP는 클러스터 어디서든 통함) SNAT이 필요 없다. SNAT은 "바깥 → 노드" 입구에서만 일어난다.
  - L7 프록시(Traefik)는 새 연결을 맺어 대신 요청하므로, **RemoteAddr이 프록시인 건 정상**이다. 그래서 원래 손님 IP는 `X-Forwarded-For` 헤더로 전달하는 관례가 있다 (`손님, 프록시1, 프록시2`처럼 쌓임).
  - **고칠 곳은 입구 한 군데.** 입구에서 SNAT이 없어지면(`externalTrafficPolicy: Local`) X-Forwarded-For가 맞게 채워지고, 앱은 그 헤더를 읽으면 된다.
  - Spring Boot: `server.forward-headers-strategy`로 헤더를 반영한다. **보안 함정:** X-Forwarded-For는 고객이 위조할 수 있으므로 믿을 수 있는 프록시가 붙인 값만 신뢰한다 (trusted proxies).
- **실무 포인트: 앱 로그에 고객 IP 대신 노드 IP가 찍힌다.** `externalTrafficPolicy: Local`로 설정하면 파드가 있는 노드만 받아서 고객 IP가 보존된다 (대신 파드 없는 노드로 온 요청은 못 받는다). 은행에서는 이상 거래 탐지와 감사 로그 때문에 고객 IP가 중요하다.

### NodePort → Traefik 연결은 어디에 설정되나
같은 Service 매니페스트의 다른 칸이다. ClusterIP와 NodePort는 **입구가 두 개인 하나의 Service**다.
```yaml
- name: web
  port: 80          # ClusterIP 입구 (10.43.212.187:80)
  nodePort: 31805   # NodePort 입구 (각 노드:31805), LoadBalancer/NodePort 타입이면 30000~32767에서 자동 배정
  targetPort: web   # 둘 다 Traefik 파드의 web(8000)으로
```
- 이 Service는 k3s가 Traefik Helm 차트를 설치하면서 `type: LoadBalancer`로 만들었다.
- iptables에서 두 입구가 같은 처리 절차로 합류한다:
  ```
  노드:31805       → KUBE-NODEPORTS → KUBE-EXT-UQMC... ─┐
  10.43.212.187:80 → KUBE-SERVICES ─────────────────────┴→ KUBE-SVC-UQMC... → Traefik 파드
  ```

### MetalLB 대신 외부 LB VM(HAProxy 등)은?
가능하고, 실무에서도 흔하다 (금융권의 하드웨어 L4 장비(F5 등)를 클러스터 앞에 두는 구조와 같다).

| | 외부 LB VM | MetalLB |
| --- | --- | --- |
| 위치 | 클러스터 밖, 노드들 앞 | 클러스터 안, 노드가 대표 IP를 맡음 |
| LB가 죽으면 | 전체 접속 불가 → VM 2대 + keepalived 필요 | 다른 노드가 IP를 이어받음 |
| k8s 연동 | 없음 (노드 변경 시 수동 수정, Service는 `<pending>`) | `type: LoadBalancer`가 그대로 동작 |
| 메모리 | VM 1~2대 추가 | 노드당 작은 파드 |

- keepalived로 LB VM을 이중화할 때 대표 IP를 넘겨받는 방법도 ARP다 (MetalLB L2 모드와 같은 원리).
- 이 프로젝트의 선택은 MetalLB: 메모리 절약, LoadBalancer 타입이 그대로 동작, "대표 IP를 맡은 노드를 죽이는" 장애 실험이 가능하다.

### 지난 프로젝트(concurrency-conquest)와의 차이
그때는 k3s 기본 servicelb(켜짐)로 노드 IP의 80/443에 직접 접속했다. `<pending>`은 없었지만 "노드 하나의 주소를 알려 줘야 하고, 그 노드가 죽으면 끝"이라는 문제는 그대로였다.

---

## 0. 두 개의 네트워크

```
┌─ 집 네트워크 172.30.1.0/24 (공유기가 아는 세계) ───────────────────┐
│  맥북   공유기(.254)   Proxmox(.200)   bank-cp(.230) ... bank-w3(.233) │
└──────────────────────────────────────────────────────────────────────┘
          ┌─ 클러스터 내부 네트워크 (노드끼리만 아는 세계) ─────┐
          │  파드 IP     10.42.0.0/16  (k3s 기본, flannel)      │
          │  서비스 IP   10.43.0.0/16  (k3s 기본)               │
          └──────────────────────────────────────────────────────┘
```

공유기와 맥북은 10.42.x.x, 10.43.x.x의 존재를 모른다. 두 세계를 잇는 것이 Service 타입이고, 온프레미스에서 마지막 조각이 MetalLB다.

## 1. 실험 결과 (Traefik 파드 10.42.3.2 on bank-w3, 서비스 10.43.212.187, NodePort 31805)

| # | 어디서 → 어디로 | 결과 | 의미 |
| --- | --- | --- | --- |
| C | 맥북 → 파드 IP | ❌ | 파드 네트워크는 클러스터 내부 전용 |
| D | 맥북 → ClusterIP | ❌ | ClusterIP도 내부 전용 |
| E | 맥북 → 노드 4대의 `:31805` | ✅ 4대 전부 | NodePort는 모든 노드에 열리고, 파드가 없는 노드로 와도 전달된다 |
| F | 노드(bank-w1) → 파드 IP / ClusterIP | ✅ | 클러스터 안에서는 둘 다 통한다 |

(404 응답 = Traefik이 대답함. 아직 라우팅 규칙이 없을 뿐 연결은 성공)

## 2. 층별 정리

### 파드 IP
- flannel(오버레이 네트워크)이 노드들 사이에 만든 가상 네트워크. 노드끼리만 길을 안다.
- 파드가 재시작되면 바뀐다 → 직접 쓰지 않는다.

### ClusterIP
- 바뀌지 않는 내부 대표 주소. 파드가 여러 개면 나눠 준다.
- **실체 없는 가상 IP.** 각 노드의 kube-proxy가 "이 IP로 가는 패킷은 이 파드들로"라는 iptables 규칙을 심어 둔 것이다.
- 서비스 간 내부 호출에 쓴다 (예: `transfer` → `http://account:8080`).

### NodePort
- 모든 노드에서 같은 포트(30000~32767)를 연다. 어느 노드로 들어와도 kube-proxy가 실제 파드로 전달한다.
- 문제: 이상한 포트 번호, **고객에게 어떤 노드 IP를 알려 줄 것인가** (그 노드가 죽으면 끝).

### LoadBalancer
- Service 타입은 겹겹이 쌓인다: **LoadBalancer ⊃ NodePort ⊃ ClusterIP**
- `type: LoadBalancer` = "누군가 외부 IP를 붙여 주세요"라는 **요청서**. k8s 자체는 처리하지 않는다.
  - 클라우드(EKS 등): 클라우드 컨트롤러가 ELB 등을 만들어 붙인다.
  - 온프레미스: 처리할 주체가 없다 → 계속 `<pending>`

## 3. ARP

같은 서브넷 안에서는 IP가 아니라 **MAC 주소**로 배달한다. 그래서 먼저 IP를 MAC으로 바꿔야 한다.

```
📢 (브로드캐스트) "172.30.1.230 가진 사람?"        ← ARP 요청
🙋 bank-cp: "나야, bc:24:11:21:b5:74"              ← ARP 응답 → 상대가 기억 (ARP 테이블)
```

실측: 맥북 `arp -an`의 `172.30.1.230 at bc:24:11:21:b5:74` = Proxmox `qm config 230`의 `net0: virtio=BC:24:11:21:B5:74`
→ Proxmox 브리지(vmbr0) 덕분에 VM은 공유기 입장에서 진짜 컴퓨터와 똑같은 네트워크 구성원이다.

아무도 갖지 않은 IP(예: .240)는 ARP에 대답하는 사람이 없어서 통신 자체가 안 된다.

## 4. MetalLB

| 기능 | 내용 |
| --- | --- |
| ① IP 할당 | `<pending>` LoadBalancer 서비스에 풀(.240~.250)에서 IP를 준다 |
| ② 알리기 (L2 모드) | 노드마다 있는 speaker 파드 중 대표 하나가 그 IP의 ARP에 **자기 노드 MAC으로 대답**한다 |

```
맥북 → "172.30.1.240 누구?" ← bank-wN speaker: "나야 (bank-wN MAC)"
맥북 → bank-wN:80 → kube-proxy → traefik 파드 (다른 노드에 있어도 됨)
```

- 대표 노드가 죽으면 다른 speaker가 이어받고 Gratuitous ARP("이제 이 IP는 내 MAC")를 보낸다 → 수 초 끊긴 뒤 복구된다. **노드가 죽어도 IP는 살아 있다.**

### L2 모드 vs BGP 모드

| | L2 모드 (이 프로젝트) | BGP 모드 |
| --- | --- | --- |
| 알리는 방법 | ARP 응답 | 라우터에 경로 광고 |
| 트래픽 | 대표 노드 1대로 집중 (병목 가능) | ECMP로 여러 노드에 분산 |
| 장애 시 | 수 초 끊김 후 다른 노드가 이어받음 | 라우터가 경로를 뺀다 |
| 필요 장비 | 없음 | BGP 지원 라우터 (데이터센터) |

L2 모드는 **장애 대비(failover)는 되지만 부하 분산은 아니다.**

### k3s servicelb를 끈 이유
servicelb(klipper)는 별도 IP 없이 노드 IP의 포트를 직접 연다. 결국 "노드 IP를 알려 주는" 방식이라 NodePort의 문제가 남는다. 실무 온프레미스에서는 MetalLB처럼 서비스 전용 IP를 주는 방식이 일반적이다.

## 5. MetalLB(L4)와 Traefik/Ingress(L7)

LB IP는 한정돼 있다(풀에 11개). Traefik 하나에만 IP를 주고, Traefik이 HTTP Host와 경로를 보고 서비스별로 나눠 준다.

```
hello.172.30.1.240.nip.io  ─┐
argocd.172.30.1.240.nip.io ─┼→ 172.30.1.240 (MetalLB, L4) → Traefik (L7) ─┬→ hello
grafana.172.30.1.240.nip.io─┘                                              ├→ argocd
                                                                            └→ grafana
```

## 6. 실무·면접 포인트
- 단골 질문: "Service 타입의 차이", "Ingress와 LoadBalancer의 차이"
- 장애 대응: "접속이 안 된다" → 바깥에서 안으로 층별 확인: LB IP(ARP) → NodePort → ClusterIP → 파드 IP → 앱. 위 실험 C~F가 그 방법이다.
- 국내 금융권은 온프레미스 비중이 높아 이 문제(LB가 없음)를 실제로 겪는다. 하드웨어 LB(F5 등)나 MetalLB를 쓴다.

## 7. 직접 해 볼 실험 (MetalLB 설치 후)
1. `kubectl get svc -n kube-system traefik` → EXTERNAL-IP가 172.30.1.240인지
2. 맥북 `arp -an | grep 172.30.1.240` → MAC이 어느 노드 것인지 (`qm config`와 비교)
3. 그 노드를 끄고 `curl`을 반복 → 몇 초 끊기는지, ARP의 MAC이 다른 노드로 바뀌는지
