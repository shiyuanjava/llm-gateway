# llm-gateway 子项目:数据库迁移 Flyway 化 设计文档

- 日期:2026-07-13
- 状态:已确认(用户逐节确认于本日)
- 范围:llm-gateway 后端、docker-compose、两侧 README;前端无改动
- 前序:安全基线、SSE 流式、Token 计数、运维硬化、响应缓存 Redis 化均已完成
- 起因:`ApiKeyHashMigration` 手写迁移(构造器跑 SQL、每次启动空跑 information_schema 探测、DDL 硬编码在 Java 里)需要清理;用户选定引入 Flyway 做系统性解法

## 1. 目标与约束

数据库结构与种子数据的唯一权威来源收敛到 Flyway 版本化脚本,应用启动时自动迁移;删除手写迁移类;compose 与本地开发建库走同一条路径。

约束:

- 零 Java 代码、纯声明式,与 SCA 终态正交(Flyway 不在 Sentinel/Nacos 替换范围内);
- 迁移失败即启动失败(fail-fast);
- 已发布的版本脚本永不修改(Flyway checksum 校验拦截)。

用户决策记录:方案 = 引入 Flyway(而非仅删迁移类或重构保留);seed = 也进版本脚本(V1 建表、V2 seed,所有环境一致);存量本地库 = 删库重建,不配 baseline-on-migrate(所有库从 V1 完整跑)。

## 2. 依赖与配置

- pom 加 `org.flywaydb:flyway-core` + `org.flywaydb:flyway-mysql`(MySQL 8 必须加后者;版本由 Spring Boot 4.1 BOM 管理,不写版本号);
- 不加任何 `spring.flyway.*` 配置 — Boot 自动配置默认 enabled、locations `classpath:db/migration`,执行顺序 DataSource 就绪 → Flyway → MyBatis-Plus/业务 Bean;
- 不配 baseline、不引入 Flyway Maven 插件。

## 3. 脚本组织

```
src/main/resources/db/migration/
├── V1__baseline_schema.sql   ← 现 schema.sql 原样搬入
└── V2__seed_demo_data.sql    ← 现 seed.sql 原样搬入
```

- 原 `schema.sql`/`seed.sql` 删除(git 历史可查),避免两份漂移;
- 脚本内容不改(保留 `IF NOT EXISTS`/`INSERT IGNORE`,幂等语句在干净库上无害);
- 未来 DDL 变更从 `V3__xxx.sql` 起步。

## 4. 删除 ApiKeyHashMigration

删 `ApiKeyHashMigration.java` + `ApiKeyHashMigrationTest.java`(migration 包随之清空、一并删除)。理由:「明文列→哈希列」迁移在所有现存环境已完成,V1 基线即终态结构(`key_hash`,无明文列),该类往后只会每次启动空跑两次 information_schema 探测。极端情况(从未迁移的古老库)启动后认证查询立刻报错暴露,届时从 git 历史找回。

## 5. Docker 编排

`docker-compose.yml` mysql 服务删掉两行 initdb 挂载(`schema.sql`/`seed.sql`);`MYSQL_DATABASE: llm_gateway` 保留 — mysql 容器只建空库,建表灌数据由 gateway 启动时 Flyway 完成;gateway healthcheck `start_period: 30s` 不动(两个脚本秒级跑完)。已有 `mysql-data` 卷的环境需 `docker compose down -v` 清卷重来(卷内旧库无 flyway 历史表)。

## 6. 本地开发与文档

本地过渡(一次性):

```sql
DROP DATABASE llm_gateway; CREATE DATABASE llm_gateway CHARACTER SET utf8mb4;
```

之后 `mvn spring-boot:run` 启动自动建表灌 seed,本地手工建表步骤从此消失。

文档同步:`llm-gateway/README.md` 三处(工程结构脚本路径、本地开发建库步骤、部署表格 mysql 行说明);根 `README.md` 本地开发一处;`application.yaml` 与 `GatewayProperties` javadoc 中「见 schema.sql / seed.sql」注释改为「见 db/migration」。

## 7. 测试与验收

- 单元测试:现有测试不连库,零影响;删 `ApiKeyHashMigrationTest`(随类删除);Flyway 脚本无 Java 逻辑,不写单测,正确性由实机验收覆盖;
- 本地验收:删库重建 → `mvn spring-boot:run` → 日志出现 Flyway `Successfully applied 2 migrations` → 管理台登录、`/v1/chat/completions` 用 seed 演示 Key 正常调用;
- compose 冒烟:`docker compose down -v && up -d --build` → 四服务健康 → 同一请求两次第二次 `cache_hit`(顺带回归 Redis 缓存)→ `flyway_schema_history` 有 V1/V2 两行 success;
- checksum 防篡改回归(可选):改一字节 V1 再启动应报错拒绝,验证后还原。

## 8. 明确排除

- undo/回滚脚本(Flyway 商业版功能,单机场景不需要);
- seed 独立 location/开关(用户已选 seed 进版本脚本;「生产删演示 Key」backlog 保留,将来可用 V3 删);
- Flyway Maven 插件(命令行迁移),只用 Boot 启动时自动迁移;
- 多数据库方言支持(只有 MySQL 8)。
