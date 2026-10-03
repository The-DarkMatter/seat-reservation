#!/usr/bin/env bash
# One-time setup of a fresh Oracle Cloud Ampere A1 VM (Ubuntu 24.04, arm64).
# Run as the default 'ubuntu' user:  curl -fsSL <raw url> | sudo bash
set -euo pipefail

REPO_URL="${REPO_URL:-https://github.com/The-DarkMatter/seat-reservation.git}"
APP_DIR=/opt/seats

echo "== Docker Engine + compose plugin"
if ! command -v docker >/dev/null; then
  curl -fsSL https://get.docker.com | sh
fi
usermod -aG docker ubuntu
systemctl enable --now docker

echo "== Firewall"
# Oracle's Ubuntu images ship iptables rules that REJECT everything except SSH,
# on top of the VCN security list. Both must allow 80/443. Insert before the
# REJECT rule and persist across reboots.
for rule in "-p tcp --dport 80" "-p tcp --dport 443" "-p udp --dport 443"; do
  # shellcheck disable=SC2086
  iptables -C INPUT -m state --state NEW $rule -j ACCEPT 2>/dev/null \
    || iptables -I INPUT 5 -m state --state NEW $rule -j ACCEPT
done
DEBIAN_FRONTEND=noninteractive apt-get install -y iptables-persistent >/dev/null
netfilter-persistent save

echo "== Kernel limits for connection bursts"
cat > /etc/sysctl.d/99-seats.conf <<'EOF'
net.core.somaxconn = 65535
net.ipv4.tcp_max_syn_backlog = 65535
net.core.netdev_max_backlog = 65535
net.ipv4.ip_local_port_range = 10240 65535
net.ipv4.tcp_tw_reuse = 1
fs.file-max = 2097152
EOF
sysctl --system >/dev/null

echo "== App checkout in $APP_DIR"
if [[ ! -d "$APP_DIR/.git" ]]; then
  git clone "$REPO_URL" "$APP_DIR"
fi
if [[ ! -f "$APP_DIR/.env" ]]; then
  cp "$APP_DIR/deploy/.env.example" "$APP_DIR/.env"
  chmod 600 "$APP_DIR/.env"
  echo "!! Fill in $APP_DIR/.env, then run: $APP_DIR/deploy/deploy.sh latest"
fi
chown -R ubuntu:ubuntu "$APP_DIR"

echo "== Done. Docker restarts the stack on reboot (restart: unless-stopped)."
