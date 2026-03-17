#!/bin/sh
# Required env vars: SSH_PORT, SSH_PASSWORD, SSH_USER

if [ ! -f /etc/ssh/ssh_host_rsa_key ]; then
    echo '[setup] Generating SSH host keys...'
    mkdir -p /etc/ssh
    ssh-keygen -A
fi

# Alpine 使用 adduser/addgroup
if ! id sshd >/dev/null 2>&1; then
    echo '[setup] Creating sshd privilege separation user...'
    mkdir -p /var/empty
    addgroup -S sshd 2>/dev/null || true
    adduser -S -H -h /var/empty -s /sbin/nologin -G sshd sshd 2>/dev/null || true
fi

# 设置 root 密码 (Alpine 方式)
if [ -n "${SSH_PASSWORD}" ]; then
    echo "[setup] Setting ${SSH_USER} password..."
    echo "${SSH_USER}:${SSH_PASSWORD}" | chpasswd
fi

CONF=/etc/ssh/sshd_config
sed -i '/^#*PermitRootLogin/d; /^#*PasswordAuthentication/d' "$CONF"
echo 'PermitRootLogin yes' >> "$CONF"
echo 'PasswordAuthentication yes' >> "$CONF"
mkdir -p /var/run/sshd /run/sshd

if [ ! -x /usr/sbin/sshd ]; then
    echo '[setup] ERROR: /usr/sbin/sshd is missing after install.'
    exit 1
fi

echo "[setup] Starting sshd on port ${SSH_PORT}..."
exec /usr/sbin/sshd -D -e -p ${SSH_PORT}
