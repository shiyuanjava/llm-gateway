#!/usr/bin/env bash
# 在 CI Job 容器内启动 MySQL 与 Redis，并等到两者真正可用。
#
# 用法（在 .gitlab-ci.yml 的 before_script 里）：
#   ci-services start
#
# 设计要点：这个脚本必须"要么成功、要么明确失败"。启动到一半就返回，会让
# 后续 mvn test 报出一堆看似业务逻辑的连接错误，掩盖真正原因。因此每个服务
# 都轮询到真正可服务为止，超时则打印日志并以非零码退出，让 Job 立刻红掉。
set -euo pipefail

MYSQL_READY_TIMEOUT="${MYSQL_READY_TIMEOUT:-90}"
REDIS_READY_TIMEOUT="${REDIS_READY_TIMEOUT:-30}"
REDIS_PORT="${REDIS_TEST_PORT:-16379}"

log() { printf '[ci-services] %s\n' "$*"; }

die() {
    printf '[ci-services] ERROR: %s\n' "$*" >&2
    exit 1
}

start_mysql() {
    log "starting mysqld"
    install -d -o mysql -g mysql /var/run/mysqld /var/log/mysql
    # init-file 在开放连接之前执行，保证第一个连接进来时账号和库都已就绪
    mysqld --user=mysql --init-file=/etc/ci/mysql-init.sql >/var/log/mysql/ci-stdout.log 2>&1 &

    local deadline=$((SECONDS + MYSQL_READY_TIMEOUT))
    while (( SECONDS < deadline )); do
        # 用真实查询而非 mysqladmin ping：ping 在 init-file 尚未跑完时就会成功，
        # 此时用密码连接仍会失败，会把竞态推到测试里去暴露。
        if mysql -h 127.0.0.1 -P 3306 -uroot -pci-test-mysql-password \
                 -e 'SELECT 1' >/dev/null 2>&1; then
            log "mysqld ready after $((SECONDS - deadline + MYSQL_READY_TIMEOUT))s"
            return 0
        fi
        sleep 1
    done

    log "mysqld failed to become ready; last 50 lines of log:"
    tail -n 50 /var/log/mysql/ci-stdout.log >&2 || true
    tail -n 50 /var/log/mysql/error.log >&2 2>/dev/null || true
    die "mysqld not ready within ${MYSQL_READY_TIMEOUT}s"
}

start_redis() {
    log "starting redis-server on port ${REDIS_PORT}"
    install -d -o redis -g redis /var/lib/redis /var/log/redis
    redis-server /etc/redis/redis-ci.conf --port "${REDIS_PORT}" --daemonize yes

    local deadline=$((SECONDS + REDIS_READY_TIMEOUT))
    while (( SECONDS < deadline )); do
        if redis-cli -h 127.0.0.1 -p "${REDIS_PORT}" ping 2>/dev/null | grep -q PONG; then
            log "redis ready on ${REDIS_PORT}"
            return 0
        fi
        sleep 1
    done

    log "redis failed to become ready; last 50 lines of log:"
    tail -n 50 /var/log/redis/redis-ci.log >&2 2>/dev/null || true
    die "redis not ready within ${REDIS_READY_TIMEOUT}s"
}

case "${1:-start}" in
    start)
        start_mysql
        start_redis
        log "all services ready"
        ;;
    *)
        die "unknown command: ${1}. usage: ci-services start"
        ;;
esac
