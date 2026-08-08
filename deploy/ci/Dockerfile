# CI 测试基础镜像：Maven + JDK 21 + MySQL + Redis 全部烤进同一个镜像。
#
# 为什么不用 GitLab services：
#   1. llm-gateway 的 RedisResponseCacheIntegrationTest 硬编码连 127.0.0.1:16379。
#      service 容器只能通过别名主机名访问，127.0.0.1 指向 Job 容器自身，接不上。
#      把中间件放进 Job 容器内，127.0.0.1 天然成立，测试源码一行都不用改。
#   2. 中间件随镜像一次性构建好，流水线不再每次拉 service 镜像、不再跑
#      "等端口通" 的探测循环，也不需要在 before_script 里临时 apt install。
#
# 构建与推送（在京东云执行，见部署手册 4.5）：
#   docker build -t 117.72.220.94:5050/ztmdcg/llm-gateway/ci-test:1 deploy/ci
#   docker push  117.72.220.94:5050/ztmdcg/llm-gateway/ci-test:1
#
# 维护：改动本文件后把 tag 递增（:2、:3 ...），再同步改两个 .gitlab-ci.yml 里的
# image。不要复用同一个 tag 覆盖推送——Runner 上可能残留旧层，会出现"改了没生效"。

FROM maven:3.9-eclipse-temurin-21

# 版本说明（与生产的差异是刻意的，见部署手册 4.5）：
#   生产 mysql:8.4 / redis:7.4-alpine，本镜像用 Ubuntu 22.04 仓库自带的
#   MySQL 8.0 与 Redis 6.x。当前 Flyway 迁移(V1..V8)不含任何 8.4 专有语法，
#   测试用到的也只是基础 SET/GET/HASH 与 flushDb，因此该差异不影响这些用例。
#   若将来迁移脚本用到 8.4 新特性，改用官方 MySQL APT 源重建本镜像。

ARG DEBIAN_FRONTEND=noninteractive
# 国内构建走阿里云源；镜像只构建一次，这里慢一点不影响流水线
RUN sed -i 's|http://archive.ubuntu.com|http://mirrors.aliyun.com|g; s|http://security.ubuntu.com|http://mirrors.aliyun.com|g' /etc/apt/sources.list \
 && apt-get update \
 && apt-get install -y --no-install-recommends \
      mysql-server \
      redis-server \
      ca-certificates \
 && rm -rf /var/lib/apt/lists/*

# Maven 走阿里云 mirror，避免每个 Job 重复写 settings.xml
RUN mkdir -p /root/.m2 \
 && printf '%s' '<settings><mirrors><mirror><id>aliyun</id><mirrorOf>central</mirrorOf><url>https://maven.aliyun.com/repository/public</url></mirror></mirrors></settings>' \
      > /root/.m2/settings.xml

# MySQL：构建期完成 datadir 初始化，容器启动时无需再花十几秒初始化
RUN rm -rf /var/lib/mysql \
 && mkdir -p /var/lib/mysql /var/run/mysqld /var/log/mysql \
 && chown -R mysql:mysql /var/lib/mysql /var/run/mysqld /var/log/mysql \
 && mysqld --initialize-insecure --user=mysql --datadir=/var/lib/mysql

# 启动时执行：设置 root 密码并建库。init-file 在开放连接之前由 mysqld 以 root 身份执行。
# 用 '%' 而非 'localhost'：测试经 TCP 连 127.0.0.1，'localhost' 账号只覆盖 socket。
COPY mysql-init.sql /etc/ci/mysql-init.sql
COPY ci-services.sh /usr/local/bin/ci-services
RUN chmod 0644 /etc/ci/mysql-init.sql && chmod 0755 /usr/local/bin/ci-services

# 仅供本机回环使用，容器内不对外监听；CI Job 结束即销毁
COPY mysqld-ci.cnf /etc/mysql/conf.d/zz-ci.cnf
COPY redis-ci.conf /etc/redis/redis-ci.conf
RUN chmod 0644 /etc/mysql/conf.d/zz-ci.cnf /etc/redis/redis-ci.conf

# 说明性声明，实际由 CI 的 before_script 调用 `ci-services start`
CMD ["bash"]
