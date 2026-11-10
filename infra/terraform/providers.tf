provider "proxmox" {
  endpoint = var.pve_endpoint

  # 최소 권한 사용자 terraform@pve의 API 토큰 (infra/bootstrap/create-terraform-token.sh로 발급)
  api_token = var.pve_api_token

  # Proxmox 기본 인증서는 자체 서명이라 검증을 끈다 (집 내부망 전용)
  insecure = true

  # 스니펫 업로드와 클라우드 이미지 디스크 임포트는 API가 아니라 SSH로 처리된다 (bpg 프로바이더 제약).
  # 그래서 이 부분만 root SSH를 쓴다. → docs/infra/README.md "알려진 한계" 참고
  ssh {
    username    = "root"
    private_key = file(pathexpand(var.ssh_private_key_path))
  }
}
