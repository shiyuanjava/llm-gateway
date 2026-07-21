#!/usr/bin/env bash
# 单节点学习/小型生产环境：安装 K3s，保留默认 Traefik 与 local-path 存储类。
set -euo pipefail

if [ "$(id -u)" -ne 0 ]; then
  echo "请使用 sudo bash deploy/scripts/install-k3s-ubuntu.sh" >&2
  exit 1
fi

curl -sfL https://get.k3s.io | sh -
systemctl enable --now k3s

echo "等待节点 Ready..."
for _ in $(seq 1 60); do
  if k3s kubectl get node 2>/dev/null | grep -q ' Ready'; then
    break
  fi
  sleep 2
done

k3s kubectl get nodes -o wide
k3s kubectl get storageclass
echo "管理员 kubeconfig：/etc/rancher/k3s/k3s.yaml"
echo "当前用户临时使用：export KUBECONFIG=/etc/rancher/k3s/k3s.yaml"

