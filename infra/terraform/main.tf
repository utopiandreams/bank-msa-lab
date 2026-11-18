locals {
  # 고정 IP는 공유기 DHCP 범위(.1~100, .128~148) 밖에서만 쓴다.
  # MetalLB 풀은 .240~.250 으로 비워 둔다.
  #
  # host: 어느 물리 호스트에 만드나. extra_labels: k3s 합류 때 붙일 라벨 (node_label 뒤에 추가).
  # 기존 4대의 extra_labels는 반드시 [] 로 둔다. 바꾸면 cloud-init 스니펫 내용이 바뀌고,
  # 스니펫이 바뀌면 Terraform이 VM을 지우고 새로 만든다 (DB 데이터 유실). 기존 노드 라벨은 kubectl로 붙였다
  # (docs/infra/README.md "노드 확장", pitfalls 25)
  nodes = {
    "bank-cp" = {
      host  = "proxmox"
      vm_id = 230, ip = "172.30.1.230", cores = 2, memory_mb = 4096, disk_gb = 30
      role  = "server", node_label = "role=control-plane", extra_labels = []
    }
    "bank-w1" = {
      host  = "proxmox"
      vm_id = 231, ip = "172.30.1.231", cores = 6, memory_mb = 8192, disk_gb = 50
      role  = "agent", node_label = "role=worker", extra_labels = []
    }
    "bank-w2" = {
      host  = "proxmox"
      vm_id = 232, ip = "172.30.1.232", cores = 6, memory_mb = 8192, disk_gb = 50
      role  = "agent", node_label = "role=worker", extra_labels = []
    }
    "bank-w3" = {
      host  = "proxmox"
      vm_id = 233, ip = "172.30.1.233", cores = 6, memory_mb = 8192, disk_gb = 50
      role  = "agent", node_label = "role=worker", extra_labels = []
    }

    # 2026-11-18 노드 확장: proxmox2 (i5-14600K 20스레드 / 31GB).
    # 메모리가 한계라 VM을 잘게 나누지 않고 큰 워커 2대. 약 5GB는 HA 때 두 번째 컨트롤 플레인 자리로 남긴다.
    # vCPU 10 x 2 = 호스트 스레드 20 (CPU는 VM끼리 나눠 쓰는 자원이라 꽉 채워도 된다. 메모리는 고정 할당이라 안 됨)
    "bank-w4" = {
      host  = "proxmox2"
      vm_id = 234, ip = "172.30.1.234", cores = 10, memory_mb = 12288, disk_gb = 80
      role  = "agent", node_label = "role=worker", extra_labels = ["topology.kubernetes.io/zone=proxmox2"]
    }
    "bank-w5" = {
      host  = "proxmox2"
      vm_id = 235, ip = "172.30.1.235", cores = 10, memory_mb = 12288, disk_gb = 80
      role  = "agent", node_label = "role=worker", extra_labels = ["topology.kubernetes.io/zone=proxmox2"]
    }
  }

  server_ip      = local.nodes["bank-cp"].ip
  ssh_public_key = trimspace(file(pathexpand(var.ssh_public_key_path)))

  # 호스트별 모듈에 공통으로 넘기는 값
  common = {
    templates_dir   = "${path.module}/templates"
    server_ip       = local.server_ip
    k3s_version     = var.k3s_version
    vm_user         = var.vm_user
    ssh_public_key  = local.ssh_public_key
    image_datastore = var.image_datastore
    vm_datastore    = var.vm_datastore
    bridge          = var.bridge
    gateway         = var.gateway
    dns_servers     = var.dns_servers
  }
}

# k3s 노드끼리 클러스터에 합류할 때 쓰는 비밀 토큰. 상태 파일에만 저장된다. 호스트가 달라도 같은 클러스터라 하나
resource "random_password" "k3s_token" {
  length  = 48
  special = false
}

module "pve1" {
  source    = "./modules/proxmox-host"
  providers = { proxmox = proxmox }

  node_name = var.pve_node
  nodes     = { for name, n in local.nodes : name => n if n.host == "proxmox" }
  k3s_token = random_password.k3s_token.result

  templates_dir   = local.common.templates_dir
  server_ip       = local.common.server_ip
  k3s_version     = local.common.k3s_version
  vm_user         = local.common.vm_user
  ssh_public_key  = local.common.ssh_public_key
  image_datastore = local.common.image_datastore
  vm_datastore    = local.common.vm_datastore
  bridge          = local.common.bridge
  gateway         = local.common.gateway
  dns_servers     = local.common.dns_servers
}

module "pve2" {
  source    = "./modules/proxmox-host"
  providers = { proxmox = proxmox.pve2 }

  node_name = var.pve2_node
  nodes     = { for name, n in local.nodes : name => n if n.host == "proxmox2" }
  k3s_token = random_password.k3s_token.result

  templates_dir   = local.common.templates_dir
  server_ip       = local.common.server_ip
  k3s_version     = local.common.k3s_version
  vm_user         = local.common.vm_user
  ssh_public_key  = local.common.ssh_public_key
  image_datastore = local.common.image_datastore
  vm_datastore    = local.common.vm_datastore
  bridge          = local.common.bridge
  gateway         = local.common.gateway
  dns_servers     = local.common.dns_servers
}

# 2026-11-18 루트 리소스를 모듈로 옮겼다. moved가 없으면 Terraform은 "옛 주소 삭제 + 새 주소 생성"으로 보고
# 기존 VM 4대를 지우고 다시 만든다. moved는 상태 파일의 주소만 바꾸고 실제 리소스는 건드리지 않는다
moved {
  from = proxmox_download_file.ubuntu
  to   = module.pve1.proxmox_download_file.ubuntu
}

moved {
  from = proxmox_virtual_environment_file.cloud_init
  to   = module.pve1.proxmox_virtual_environment_file.cloud_init
}

moved {
  from = proxmox_virtual_environment_vm.node
  to   = module.pve1.proxmox_virtual_environment_vm.node
}
