# llm-gateway 数据库迁移 Flyway 化 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 数据库结构与种子数据收敛到 Flyway 版本化脚本(V1 建表、V2 seed),应用启动自动迁移;删除手写的 `ApiKeyHashMigration`;compose 与本地建库走同一路径。

**Architecture:** 纯声明式:pom 加 flyway-core + flyway-mysql(Boot BOM 管版本),`schema.sql`/`seed.sql` 原样搬入 `db/migration/`,不加任何 `spring.flyway.*` 配置(Boot 自动配置默认即所需:enabled、locations `classpath:db/migration`、DataSource 就绪后 MyBatis 之前执行、失败即启动失败)。compose 的 mysql 只建空库,initdb 挂载删除。

**Tech Stack:** Spring Boot 4.1 自动配置 + Flyway 11(BOM 管理)、MySQL 8、docker compose。

**Spec:** `docs/superpowers/specs/2026-07-13-llm-gateway-flyway-migration-design.md`

**构建命令(重要):** mvnw 已损坏,统一用系统 Maven + 指定 JDK:
```bash
cd C:/practice/llm-gateway && JAVA_HOME=~/.jdks/ms-21.0.10 mvn test
```
本机 curl 必须加 `--noproxy '*'`;本地 MySQL root/123456;起本地服务前先确认 8080 无旧进程残留(曾因此误判)。

## 文件结构

| 文件 | 动作 | 职责 |
|---|---|---|
| `llm-gateway/pom.xml` | 修改 | 加 flyway-core、flyway-mysql |
| `llm-gateway/src/main/resources/schema.sql` | git mv | → `db/migration/V1__baseline_schema.sql` |
| `llm-gateway/src/main/resources/seed.sql` | git mv | → `db/migration/V2__seed_demo_data.sql` |
| `llm-gateway/src/main/java/com/llm/gateway/migration/ApiKeyHashMigration.java` | 删除 | 使命已完成的手写迁移 |
| `llm-gateway/src/test/java/com/llm/gateway/migration/ApiKeyHashMigrationTest.java` | 删除 | 随类删除(1 个测试) |
| `llm-gateway/docker-compose.yml` | 修改 | 删两行 initdb 挂载 |
| `llm-gateway/README.md` | 修改 | 三处建库口径 + 部署表 |
| `README.md`(仓库根) | 修改 | 本地开发一处 |
| `llm-gateway/src/main/resources/application.yaml` | 修改 | 注释「见 schema.sql / seed.sql」→「见 db/migration」 |
| `llm-gateway/src/main/java/com/llm/gateway/config/GatewayProperties.java` | 修改 | javadoc 同上 |

任务无新增 Java 行为(声明式脚本),TDD 不适用于 SQL 搬迁;每步验收 = 测试全绿 / 实机验证。

---

### Task 1: Flyway 依赖与脚本搬迁

**Files:**
- Modify: `llm-gateway/pom.xml`
- Rename: `llm-gateway/src/main/resources/schema.sql` → `llm-gateway/src/main/resources/db/migration/V1__baseline_schema.sql`
- Rename: `llm-gateway/src/main/resources/seed.sql` → `llm-gateway/src/main/resources/db/migration/V2__seed_demo_data.sql`

- [ ] **Step 1: pom.xml 加依赖**

在 `spring-boot-starter-data-redis` 依赖块之后插入(版本由 Boot BOM 管理,不写版本号):

```xml
        <!-- Flyway 数据库迁移:启动时自动执行 db/migration 下的版本脚本;MySQL 方言在独立模块 -->
        <dependency>
            <groupId>org.flywaydb</groupId>
            <artifactId>flyway-core</artifactId>
        </dependency>
        <dependency>
            <groupId>org.flywaydb</groupId>
            <artifactId>flyway-mysql</artifactId>
            <scope>runtime</scope>
        </dependency>
```

- [ ] **Step 2: 脚本搬迁(git mv,保留历史)**

```bash
cd C:/practice/llm-gateway/src/main/resources && mkdir -p db/migration && git mv schema.sql db/migration/V1__baseline_schema.sql && git mv seed.sql db/migration/V2__seed_demo_data.sql
```

脚本内容一个字节都不改(保留 `IF NOT EXISTS`/`INSERT IGNORE`,干净库上无害)。

- [ ] **Step 3: 全量测试确认无回归**

```bash
cd C:/practice/llm-gateway && JAVA_HOME=~/.jdks/ms-21.0.10 mvn test
```

Expected: `BUILD SUCCESS`,158 tests,0 failures。(单元测试不连库,Flyway 自动配置只在真实 DataSource 启动路径生效,不影响测试。)

- [ ] **Step 4: Commit**

```bash
cd C:/practice && git add llm-gateway/pom.xml llm-gateway/src/main/resources/db/migration/ && git commit -m "feat: 引入 Flyway,schema/seed 搬入版本化迁移脚本 V1/V2"
```

(git mv 的删除侧已在暂存区,`git add db/migration/` 会一并覆盖;若 status 仍显示旧路径未暂存,补 `git add llm-gateway/src/main/resources/schema.sql llm-gateway/src/main/resources/seed.sql`。)

---

### Task 2: 删除 ApiKeyHashMigration

**Files:**
- Delete: `llm-gateway/src/main/java/com/llm/gateway/migration/ApiKeyHashMigration.java`
- Delete: `llm-gateway/src/test/java/com/llm/gateway/migration/ApiKeyHashMigrationTest.java`

- [ ] **Step 1: 删除类与测试(migration 包随之清空)**

```bash
cd C:/practice && git rm llm-gateway/src/main/java/com/llm/gateway/migration/ApiKeyHashMigration.java llm-gateway/src/test/java/com/llm/gateway/migration/ApiKeyHashMigrationTest.java
```

- [ ] **Step 2: 确认无残留引用**

```bash
cd C:/practice/llm-gateway && grep -rn "ApiKeyHashMigration" src/ || echo "no references"
```

Expected: `no references`。

- [ ] **Step 3: 全量测试**

```bash
cd C:/practice/llm-gateway && JAVA_HOME=~/.jdks/ms-21.0.10 mvn test
```

Expected: `BUILD SUCCESS`,157 tests(158 减去删除的 1 个),0 failures。

- [ ] **Step 4: Commit**

```bash
cd C:/practice && git commit -m "refactor: 删除 ApiKeyHashMigration——使命已完成,V1 基线即终态结构"
```

---

### Task 3: compose 与文档同步

**Files:**
- Modify: `llm-gateway/docker-compose.yml`
- Modify: `llm-gateway/README.md`
- Modify: `README.md`(仓库根)
- Modify: `llm-gateway/src/main/resources/application.yaml`
- Modify: `llm-gateway/src/main/java/com/llm/gateway/config/GatewayProperties.java`

- [ ] **Step 1: compose 删 initdb 挂载**

mysql 服务 `volumes` 由三行改为一行(删 schema/seed 两行挂载及其注释;`MYSQL_DATABASE: llm_gateway` 保留——mysql 只建空库,建表灌数据由 gateway 启动时 Flyway 完成):

```yaml
    volumes:
      - mysql-data:/var/lib/mysql
```

- [ ] **Step 2: compose 语法校验**

```bash
cd C:/practice/llm-gateway && MYSQL_ROOT_PASSWORD=x GATEWAY_JWT_SECRET=x docker compose config --quiet && echo "COMPOSE OK"
```

Expected: `COMPOSE OK`。

- [ ] **Step 3: llm-gateway/README.md 三处更新**

(1) 工程结构处(约 44 行):

```markdown
- 建表与种子脚本:`src/main/resources/db/migration/`(Flyway 版本化:`V1__baseline_schema.sql` 建表、`V2__seed_demo_data.sql` 演示数据;启动自动迁移,已发布脚本永不修改,变更加 V3+)。
```

(2) 本地开发「1. 初始化数据库」(约 113-119 行)整段改为:

```markdown
### 1. 初始化数据库

```bash
mysql -uroot -p -e "CREATE DATABASE IF NOT EXISTS llm_gateway DEFAULT CHARACTER SET utf8mb4;"
```

只需建空库——启动时 Flyway 自动建表并灌入种子数据(脚本在 `src/main/resources/db/migration/`)。
```

(3) 部署表格 mysql 行(约 179 行)改为,并在其后补一行 redis(上一子项目加的服务,表格漏了):

```markdown
| mysql | 3306(仅容器网络) | 数据持久化在 named volume `mysql-data`;建表/种子由 gateway 启动时 Flyway 自动迁移 |
| redis | 6379(仅容器网络) | 响应缓存(纯缓存不持久化,LRU 256MB);gateway 对其故障 fail-open |
```

- [ ] **Step 4: 根 README + 注释同步**

根 `README.md` 本地开发注释行改为:

```markdown
# 后端(需 JDK 21 + 本地 MySQL 8,建空库 llm_gateway 即可,启动自动执行 Flyway 迁移)
```

`llm-gateway/src/main/resources/application.yaml` 67 行注释改为:

```yaml
# 说明：API Key、路由规则、计费单价已迁移到数据库（见 db/migration 迁移脚本），不再写在这里。
```

`GatewayProperties.java` 11 行 javadoc 中 `（见 {@code schema.sql}）` 改为 `（见 {@code db/migration}）`。

- [ ] **Step 5: 编译确认 javadoc 改动无破坏 + Commit**

```bash
cd C:/practice/llm-gateway && JAVA_HOME=~/.jdks/ms-21.0.10 mvn -q compile && cd C:/practice && git add llm-gateway/docker-compose.yml llm-gateway/README.md README.md llm-gateway/src/main/resources/application.yaml llm-gateway/src/main/java/com/llm/gateway/config/GatewayProperties.java && git commit -m "docs: 建库口径切换到 Flyway,compose 去掉 initdb 挂载"
```

---

### Task 4: 本地实机验收(删库重建 + 启动迁移)

**Files:** 无代码改动。前置:本地 MySQL 8 运行中(root/123456);8080 端口无残留进程。

- [ ] **Step 1: 确认 8080 空闲**

```bash
netstat -ano | grep ":8080.*LISTENING" || echo "8080 free"
```

Expected: `8080 free`(否则先结束占用进程,曾因旧进程残留误判新代码未生效)。

- [ ] **Step 2: 删库重建(一次性过渡,本地请求日志等数据丢弃——用户已确认)**

```bash
mysql -uroot -p123456 -e "DROP DATABASE IF EXISTS llm_gateway; CREATE DATABASE llm_gateway DEFAULT CHARACTER SET utf8mb4;"
```

- [ ] **Step 3: 启动服务,观察 Flyway 迁移日志**

```bash
cd C:/practice/llm-gateway && JAVA_HOME=~/.jdks/ms-21.0.10 mvn spring-boot:run
```

Expected 日志(启动早期):`Migrating schema \`llm_gateway\` to version "1 - baseline schema"`、`... version "2 - seed demo data"`、`Successfully applied 2 migrations`。

- [ ] **Step 4: 冒烟验证 seed 生效**

另开终端:

```bash
curl --noproxy '*' -s http://localhost:8080/v1/chat/completions -H "Authorization: Bearer sk-demo-tenant-a" -H "Content-Type: application/json" -d '{"model":"cheap","messages":[{"role":"user","content":"flyway smoke"}]}' | head -c 150
mysql -uroot -p123456 llm_gateway -e "SELECT version, description, success FROM flyway_schema_history;"
```

Expected: 200 返回 mock 响应(seed 的演示 Key 与路由生效);history 表两行 V1/V2 均 `success=1`。验证后停掉 spring-boot:run。

- [ ] **Step 5: 无 commit(纯验证)**

验证结果记录到最终报告。

---

### Task 5: compose 实机冒烟

**Files:** 无代码改动。前置:Docker Desktop 运行中。

- [ ] **Step 1: 临时 .env + 清卷全新起(冒烟后删 .env,既有约定)**

```bash
cd C:/practice/llm-gateway && printf 'MYSQL_ROOT_PASSWORD=smoke-root-pw-2026\nGATEWAY_JWT_SECRET=smoke-jwt-secret-0123456789abcdef0123456789abcdef\nADMIN_USERNAME=admin\nADMIN_PASSWORD=smoke-admin-pw-2026\n' > .env && docker compose down -v; docker compose up -d --build
sleep 30 && docker compose ps --format "table {{.Name}}\t{{.Status}}"
```

Expected: mysql/redis/gateway healthy,ui Up。(mysql 空库 → gateway 启动时 Flyway 建表,若 gateway unhealthy 先看 `docker compose logs gateway` 的 Flyway 段。)

- [ ] **Step 2: 验证迁移历史 + 请求链路 + Redis 缓存回归**

```bash
docker compose exec -T mysql sh -c 'exec mysql -uroot -p"$MYSQL_ROOT_PASSWORD" llm_gateway -e "SELECT version, description, success FROM flyway_schema_history;"' 2>/dev/null
for i in 1 2; do curl --noproxy '*' -s http://localhost:8081/v1/chat/completions -H "Authorization: Bearer sk-demo-tenant-a" -H "Content-Type: application/json" -d '{"model":"cheap","messages":[{"role":"user","content":"flyway compose smoke"}]}' | head -c 120; echo; done
docker compose exec -T mysql sh -c 'exec mysql -uroot -p"$MYSQL_ROOT_PASSWORD" llm_gateway -e "SELECT id, status FROM request_log ORDER BY id DESC LIMIT 2;"' 2>/dev/null
```

Expected: history 两行 success=1;两次 200 且响应 id 相同;request_log 第二行 `cache_hit`(Redis 缓存回归)。

- [ ] **Step 3(可选): checksum 防篡改回归**

```bash
cd C:/practice/llm-gateway && printf '\n-- tamper test\n' >> src/main/resources/db/migration/V1__baseline_schema.sql && docker compose up -d --build gateway 2>&1 | tail -2; sleep 20 && docker compose logs gateway 2>&1 | grep -i "checksum" | tail -2 && git checkout -- src/main/resources/db/migration/V1__baseline_schema.sql && docker compose up -d --build gateway
```

Expected: 日志出现 `Migration checksum mismatch`、gateway 拒绝启动;还原文件后重启恢复正常。

- [ ] **Step 4: 清理**

```bash
cd C:/practice/llm-gateway && docker compose down -v && rm .env
```

- [ ] **Step 5: 冒烟结论记录**

四条验收(本地迁移、compose 迁移、seed 链路、缓存回归)结果回报用户;有问题回对应 Task 修。

---

## Self-Review 记录

- **Spec 覆盖**:§2 依赖(Task 1)、§3 脚本组织(Task 1)、§4 删迁移类(Task 2)、§5 compose(Task 3)、§6 本地与文档(Task 3/4)、§7 验收(Task 4/5,含可选 checksum)、§8 排除项无任务(正确)。
- **占位符**:无;文档改动均给出目标文案原文。
- **一致性**:V1/V2 文件名、`flyway_schema_history` 表名、演示 Key `sk-demo-tenant-a`/模型 `cheap` 各任务一致;Task 5 依赖 Task 3 的 compose 改动(顺序执行保证)。
