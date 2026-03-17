#!/bin/sh
# Alpine Linux package installation script

# 配置 Alpine 镜像源（使用阿里云镜像加速）
echo "https://mirrors.aliyun.com/alpine/v3.21/main" > /etc/apk/repositories
echo "https://mirrors.aliyun.com/alpine/v3.21/community" >> /etc/apk/repositories

apk update

# 安装最小必要软件包
apk add --no-cache openssh bash

echo '[init] package installation finished.'
