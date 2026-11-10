output "nodes" {
  description = "노드 이름 → IP"
  value       = { for name, n in local.nodes : name => n.ip }
}

output "ssh" {
  description = "노드 접속 명령"
  value       = { for name, n in local.nodes : name => "ssh ${var.vm_user}@${n.ip}" }
}

output "next_step" {
  value = "k3s 설치에 몇 분 걸린다. 이후 ../scripts/fetch-kubeconfig.sh 실행"
}
