# Proxmox 호스트 한 대에 k3s 노드 VM들을 만드는 묶음.
# 호스트마다 API 주소(프로바이더)가 달라서, 루트에서 호스트별로 이 모듈을 한 번씩 부른다.
# (두 호스트는 Proxmox 클러스터로 묶지 않은 독립 호스트다. 2대 클러스터는 quorum 문제가 있어서)
#
# 2026-11-18 루트 main.tf에서 그대로 옮겨 왔다. 기존 VM이 다시 만들어지지 않도록
# 리소스 내용은 한 글자도 바꾸지 않았고, 상태 주소 이동은 루트의 moved 블록이 한다.

# Ubuntu 24.04 클라우드 이미지를 Proxmox가 직접 내려받는다.
# 수동 템플릿 VM 없이, 빈 Proxmox에서 apply 한 번으로 전부 재현되게 하기 위함.
resource "proxmox_download_file" "ubuntu" {
  content_type = "iso"
  datastore_id = var.image_datastore
  node_name    = var.node_name
  url          = "https://cloud-images.ubuntu.com/noble/current/noble-server-cloudimg-amd64.img"
  file_name    = "noble-server-cloudimg-amd64.img"
  overwrite    = false
}

# VM별 cloud-init user-data (첫 부팅 때 계정, 패키지, k3s 설치)
resource "proxmox_virtual_environment_file" "cloud_init" {
  for_each = var.nodes

  content_type = "snippets"
  datastore_id = var.image_datastore
  node_name    = var.node_name

  source_raw {
    file_name = "${each.key}-user-data.yaml"
    data = templatefile("${var.templates_dir}/k3s-${each.value.role}.yaml.tftpl", {
      hostname     = each.key
      user         = var.vm_user
      ssh_key      = var.ssh_public_key
      ip           = each.value.ip
      node_label   = each.value.node_label
      extra_labels = each.value.extra_labels
      k3s_version  = var.k3s_version
      k3s_token    = var.k3s_token
      server_ip    = var.server_ip
    })
  }
}

resource "proxmox_virtual_environment_vm" "node" {
  for_each = var.nodes

  name      = each.key
  node_name = var.node_name
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
