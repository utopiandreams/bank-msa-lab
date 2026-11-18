variable "node_name" {
  description = "Proxmox 노드 이름 (호스트의 hostname)"
  type        = string
}

variable "nodes" {
  description = "이 호스트에 만들 k3s 노드 VM들"
  type = map(object({
    vm_id        = number
    ip           = string
    cores        = number
    memory_mb    = number
    disk_gb      = number
    role         = string # server | agent
    node_label   = string
    extra_labels = list(string)
  }))
}

variable "templates_dir" { type = string }
variable "server_ip" { type = string }
variable "k3s_version" { type = string }
variable "vm_user" { type = string }
variable "ssh_public_key" { type = string }
variable "image_datastore" { type = string }
variable "vm_datastore" { type = string }
variable "bridge" { type = string }
variable "gateway" { type = string }
variable "dns_servers" { type = list(string) }

variable "k3s_token" {
  type      = string
  sensitive = true
}
