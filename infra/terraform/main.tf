locals {
  # 고정 IP는 공유기 DHCP 범위(.1~100, .128~148) 밖에서만 쓴다.
  # MetalLB 풀은 .240~.250 으로 비워 둔다.
  nodes = {
    "bank-cp" = {
      vm_id = 230, ip = "172.30.1.230", cores = 2, memory_mb = 4096, disk_gb = 30
      role  = "server", node_label = "role=control-plane"
    }
    "bank-w1" = {
      vm_id = 231, ip = "172.30.1.231", cores = 6, memory_mb = 8192, disk_gb = 50
      role  = "agent", node_label = "role=worker"
    }
    "bank-w2" = {
      vm_id = 232, ip = "172.30.1.232", cores = 6, memory_mb = 8192, disk_gb = 50
      role  = "agent", node_label = "role=worker"
    }
    "bank-w3" = {
      vm_id = 233, ip = "172.30.1.233", cores = 6, memory_mb = 8192, disk_gb = 50
      role  = "agent", node_label = "role=worker"
    }
  }

  server_ip      = local.nodes["bank-cp"].ip
  ssh_public_key = trimspace(file(pathexpand(var.ssh_public_key_path)))
}

# Ubuntu 24.04 클라우드 이미지를 Proxmox가 직접 내려받는다.
# 수동 템플릿 VM 없이, 빈 Proxmox에서 apply 한 번으로 전부 재현되게 하기 위함.
resource "proxmox_download_file" "ubuntu" {
  content_type = "iso"
  datastore_id = var.image_datastore
  node_name    = var.pve_node
  url          = "https://cloud-images.ubuntu.com/noble/current/noble-server-cloudimg-amd64.img"
  file_name    = "noble-server-cloudimg-amd64.img"
  overwrite    = false
}

# k3s 노드끼리 클러스터에 합류할 때 쓰는 비밀 토큰. 상태 파일에만 저장된다.
resource "random_password" "k3s_token" {
  length  = 48
  special = false
}

# VM별 cloud-init user-data (첫 부팅 때 계정, 패키지, k3s 설치)
resource "proxmox_virtual_environment_file" "cloud_init" {
  for_each = local.nodes

  content_type = "snippets"
  datastore_id = var.image_datastore
  node_name    = var.pve_node

  source_raw {
    file_name = "${each.key}-user-data.yaml"
    data = templatefile("${path.module}/templates/k3s-${each.value.role}.yaml.tftpl", {
      hostname    = each.key
      user        = var.vm_user
      ssh_key     = local.ssh_public_key
      ip          = each.value.ip
      node_label  = each.value.node_label
      k3s_version = var.k3s_version
      k3s_token   = random_password.k3s_token.result
      server_ip   = local.server_ip
    })
  }
}

resource "proxmox_virtual_environment_vm" "node" {
  for_each = local.nodes

  name      = each.key
  node_name = var.pve_node
  vm_id     = each.value.vm_id
  tags      = ["bank-msa-lab", "k3s-${each.value.role}"]

  on_boot = false # 실험할 때만 켠다 (전기료)
  started = true

  scsi_hardware = "virtio-scsi-single"

  # cloud-init이 qemu-guest-agent를 설치하면 Proxmox가 IP 등을 읽을 수 있다
  agent {
    enabled = true
    timeout = "15m"
  }

  cpu {
    cores = each.value.cores
    type  = "host"
  }

  memory {
    dedicated = each.value.memory_mb # 벌루닝 없이 고정 할당 (JVM·Kafka가 예측 가능하게)
  }

  disk {
    datastore_id = var.vm_datastore
    file_id      = proxmox_download_file.ubuntu.id
    interface    = "scsi0"
    size         = each.value.disk_gb
    discard      = "on"
    iothread     = true
    ssd          = true
  }

  network_device {
    bridge = var.bridge
  }

  # 클라우드 이미지는 시리얼 콘솔을 기대한다
  serial_device {}

  vga {
    type = "serial0"
  }

  operating_system {
    type = "l26"
  }

  initialization {
    datastore_id = var.vm_datastore

    ip_config {
      ipv4 {
        address = "${each.value.ip}/24"
        gateway = var.gateway
      }
    }

    dns {
      servers = var.dns_servers
    }

    # user_data_file_id를 쓰면 user_account 블록은 무시되므로 계정은 스니펫에서 만든다
    user_data_file_id = proxmox_virtual_environment_file.cloud_init[each.key].id
  }
}
