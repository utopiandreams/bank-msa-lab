variable "pve_endpoint" {
  description = "Proxmox API 주소"
  type        = string
  default     = "https://172.30.1.200:8006/"
}

variable "pve_api_token" {
  description = "Proxmox API 토큰. 형식: terraform@pve!provider=<uuid>"
  type        = string
  sensitive   = true
}

variable "pve_node" {
  description = "Proxmox 노드 이름"
  type        = string
  default     = "proxmox"
}

variable "ssh_private_key_path" {
  description = "Proxmox root와 VM 접속에 쓰는 SSH 개인키"
  type        = string
  default     = "~/.ssh/id_ed25519"
}

variable "ssh_public_key_path" {
  description = "VM 계정에 등록할 SSH 공개키"
  type        = string
  default     = "~/.ssh/id_ed25519.pub"
}

variable "vm_user" {
  description = "VM 로그인 계정"
  type        = string
  default     = "ubuntu"
}

variable "image_datastore" {
  description = "클라우드 이미지와 cloud-init 스니펫을 둘 스토리지 (iso, snippets 콘텐츠 허용 필요)"
  type        = string
  default     = "local"
}

variable "vm_datastore" {
  description = "VM 디스크 스토리지"
  type        = string
  default     = "local-lvm"
}

variable "bridge" {
  type    = string
  default = "vmbr0"
}

variable "gateway" {
  type    = string
  default = "172.30.1.254"
}

variable "dns_servers" {
  type    = list(string)
  default = ["172.30.1.254", "8.8.8.8"]
}

variable "k3s_version" {
  description = "k3s 버전 고정 (재현성). 2026-11-11 stable 채널 기준"
  type        = string
  default     = "v1.37.2+k3s1"
}
