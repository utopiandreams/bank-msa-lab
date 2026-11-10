# GitOps와 Argo CD는 어떻게 배포하나

> 한 줄 요약: **Git = 주문서 원본, Argo CD = 주문서를 클러스터에 붙여 주는 사람, 쿠버네티스 = 주문서대로 일하는 공장.**
> Argo CD의 기본 동작은 이미지가 아니라 **Git의 매니페스트(YAML)와 클러스터 상태를 비교해 Git 쪽으로 맞추는 것**이다.

## 1. Argo CD의 동작

```
 Git (원하는 상태)                클러스터 (실제 상태)
   replicas: 3                       replicas: 2
   WHOAMI_NAME: hello v2             WHOAMI_NAME: hello v1
            └────────► Argo CD ◄────────┘
                 ① 비교 (diff)
                 ② 다르면 Git 쪽으로 적용 (kubectl apply와 같은 일)
```

| 부품 | 하는 일 |
| --- | --- |
| repo-server | Git을 받아 매니페스트를 만든다 (일반 YAML은 그대로, Helm 차트는 YAML로 펼침) |
| application-controller | 결과와 클러스터 실제 상태를 비교하고, 다르면 적용한다 (기본 3분 주기, 또는 Refresh) |
| server | 웹 UI / API |

- 자동 동기화 옵션: `prune`(Git에서 지운 건 클러스터에서도 지움), `selfHeal`(누가 손으로 바꾸면 Git 상태로 되돌림)

## 2. Argo CD는 파드를 직접 만들지 않는다

```
Argo CD                     : Deployment(주문서)를 v2, 3개로 고쳐 씀
Deployment 컨트롤러 (k8s)    : 새 ReplicaSet(v2)을 만들고, v2 파드를 하나 띄우면 v1을 하나 끄는 롤링 업데이트
스케줄러 (k8s)               : 새 파드를 노드에 분산 배치
```

실측 (2026-11-11, 커밋 5d68f1b push):
```
hello-5b8d958f58   0개   hello v1   ← 옛 ReplicaSet. 파드 0개, 롤백용 기록으로 남음
hello-854c68fd55   3개   hello v2   ← 새 ReplicaSet. 파드는 w1, w2, w3에 하나씩 (cp는 taint로 제외)
```
- GitOps에서 롤백은 `git revert` → push로 한다 (Git 이력과 클러스터를 항상 일치시키기 위함).

## 3. 이미지가 바뀌는 배포 (2주 차부터 서비스 배포에 쓸 흐름)

```
① 코드 push
② CI (GitHub Actions 등): 빌드 → 이미지 → 레지스트리 업로드 (예: ghcr.io/<owner>/account:<커밋해시>)
③ 매니페스트의 image 태그를 새 값으로 수정 (Git 커밋)
④ Argo CD가 감지해 적용 → 쿠버네티스가 롤링 업데이트
```

- CI는 "이미지 만들기", CD(Argo CD)는 "Git대로 맞추기"로 역할이 나뉜다.
- ③ 담당자 선택지: CI가 매니페스트를 직접 커밋 (가장 흔함) / **Argo CD Image Updater** (레지스트리의 새 태그를 감지해 ③을 대신해 주는 별도 도구)
- **`latest` 태그를 쓰지 않는 이유:** 매니페스트 글자가 바뀌지 않아 Argo CD가 변화를 감지하지 못하고, 지금 어떤 버전이 떠 있는지 추적할 수 없다. 태그는 커밋 해시로 단다.
- 미정 (2주 차에 결정): 레지스트리(GHCR 등), ③을 CI가 할지 Image Updater가 할지

## 4. 실무 포인트
- **배포 = 커밋.** 누가, 언제, 무엇을 배포했는지 Git에 남는다 → 금융권 변경 관리와 감사 대응에 유리하다.
- **Drift 방지:** 운영 환경을 손으로 고치면 selfHeal이 되돌린다 → "손으로 고친 게 다음 배포 때 날아갔다" 같은 사고를 구조적으로 막는다.
