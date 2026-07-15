-- 演示 Key 门禁:V2 的 sk-demo-tenant-a/b 是公开明文,不允许存在于生产环境。
-- 由 Flyway 占位符 purge_demo_api_keys 控制(application.yaml 默认 false 保留本地演示,
-- prod profile 置 true)。注意:版本化迁移只执行一次,按首次执行时所在环境的取值生效;
-- 生产库首次以 prod profile 启动即被清除,本地开发库不受影响。

DELETE FROM api_key
WHERE key_hash IN (SHA2('sk-demo-tenant-a', 256), SHA2('sk-demo-tenant-b', 256))
  AND '${purge_demo_api_keys}' = 'true';
