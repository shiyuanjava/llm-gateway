-- CI 测试容器的 MySQL 启动初始化。由 mysqld --init-file 在开放连接前以 root 执行。
-- 只用于一次性测试容器：密码是固定的测试值，容器内仅监听 127.0.0.1，Job 结束即销毁。
-- 不要把这里的账号或密码用于任何真实环境。

-- 测试经 TCP 连 127.0.0.1，MySQL 的 'localhost' 账号只覆盖 socket 连接，故用 '%'。
ALTER USER 'root'@'localhost' IDENTIFIED BY 'ci-test-mysql-password';
CREATE USER IF NOT EXISTS 'root'@'%' IDENTIFIED BY 'ci-test-mysql-password';
GRANT ALL PRIVILEGES ON *.* TO 'root'@'%' WITH GRANT OPTION;

-- 两个项目各自的库，同一个镜像同时服务 llm-gateway 与软项智训
CREATE DATABASE IF NOT EXISTS llm_gateway
  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE IF NOT EXISTS soft_training
  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

FLUSH PRIVILEGES;
