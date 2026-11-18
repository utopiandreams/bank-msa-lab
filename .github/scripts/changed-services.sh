#!/usr/bin/env bash
# 이번 푸시에서 다시 빌드할 서비스를 JSON 배열로 출력한다 (예: ["core","transfer"]). CI의 build matrix가 이걸 쓴다.
#   사용: changed-services.sh <이전 커밋> <이번 커밋>
#
# 모노레포 CI의 고전적 문제: 레포가 하나라 "푸시됐다"만으로는 어느 서비스가 바뀌었는지 모른다 → 매번 전부 빌드·재배포.
# 판정 규칙 (경로 필터 + 의존성):
#   services/<서비스>/**               → 그 서비스
#   libs/<라이브러리>/**                → 그 라이브러리를 의존하는 서비스만 (각 서비스 build.gradle.kts의 project(":libs:<이름>")로 찾는다)
#   빌드 공통 파일(build-logic, gradle, settings, Dockerfile, CI 자체) → 전부
#   이전 커밋을 알 수 없음(새 브랜치 첫 푸시·강제 푸시·수동 실행)        → 전부 (애매하면 많이 빌드하는 쪽이 안전)
set -euo pipefail

before="${1:-}"
after="${2:-HEAD}"

# 서비스 = services/ 아래 build.gradle.kts가 있는 폴더. 새 서비스를 추가해도 이 스크립트는 안 고친다
all=()
for d in services/*/; do
  s="$(basename "$d")"
  [[ -f "services/$s/build.gradle.kts" ]] && all+=("$s")
done

emit() {   # 중복 제거·정렬 후 JSON 배열
  local json="" s
  for s in $(printf '%s\n' "$@" | grep -v '^$' | sort -u); do json+="\"$s\","; done
  echo "[${json%,}]"
}

if [[ -z "$before" || "$before" =~ ^0+$ ]] || ! git cat-file -e "${before}^{commit}" 2>/dev/null; then
  echo "이전 커밋을 알 수 없음 → 전부" >&2
  emit "${all[@]}"; exit 0
fi

changed="$(git diff --name-only "$before" "$after")"

if grep -qE '^(build-logic/|gradle/|gradlew|settings\.gradle\.kts|build\.gradle\.kts|deploy/docker/|\.github/workflows/services\.yaml|\.github/scripts/)' <<<"$changed"; then
  echo "빌드 공통 파일 변경 → 전부" >&2
  emit "${all[@]}"; exit 0
fi

out=()
for s in "${all[@]}"; do
  grep -q "^services/$s/" <<<"$changed" && out+=("$s")
done

# 바뀐 라이브러리마다 그걸 쓰는 서비스를 추가
for lib in $(grep -oE '^libs/[^/]+' <<<"$changed" | cut -d/ -f2 | sort -u); do
  for s in "${all[@]}"; do
    grep -q "project(\":libs:$lib\")" "services/$s/build.gradle.kts" && out+=("$s")
  done
done

emit "${out[@]+"${out[@]}"}"
