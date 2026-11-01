# 性能指标与慢查询采集（2026-11-01）

目标：先用一周真实数据找到瓶颈，再谈优化；不先改索引、不加机器。

本页分三块：

1. 应用指标（随 jar 启用，本次已改代码）
2. 5 分钟采样定时器（可选安装，落盘保留 8 天）
3. MySQL 慢查询日志（宝塔面板配置，需手工开启）

---

## 1. 应用指标（已启用）

代码侧改动：

- `backend/pom.xml` 增加 `micrometer-registry-prometheus`；
- `application.yml` 暴露 `health,info,metrics,prometheus`；
- `http.server.requests` 记录 p50/p95/p99 直方图与 100ms/300ms/1s SLA；
- `WriteRateLimitInterceptor` 增加两个计数器：
  - `campus_rate_limit_blocked_total{action="..."}`：被限流次数
  - `campus_rate_limit_fail_open_total{action="..."}`：Redis 不可用时的 fail-open 次数

### 在 ECS 本机验证

宝塔「终端」执行：

```bash
curl -s http://127.0.0.1:8080/actuator/health
curl -s http://127.0.0.1:8080/actuator/prometheus | head -n 20
```

只看关键指标：

```bash
curl -s http://127.0.0.1:8080/actuator/prometheus | grep -E \
  'http_server_requests_seconds|hikaricp_connections|tomcat_threads_|campus_rate_limit'
```

按名字查单个指标（排障用）：

```bash
curl -s 'http://127.0.0.1:8080/actuator/metrics/http.server.requests'
```

### 安全边界

- Nginx 只把 `/healthz` 反代到 `/actuator/health`；**没有**给 `/actuator/` 配置 location。
  公网请求 `/actuator/prometheus` 会落到 SPA 回退，返回 `index.html`，不会泄露指标。
- 不要把 `metrics` / `prometheus` 加进 Nginx；如果以后接 Prometheus，走内网/VPN 或
  安全组白名单，不要直接对公网开放。
- 指标里只有接口名、耗时、线程/连接池数量，不含用户 id、邮箱、帖子内容。

---

## 2. 安装 5 分钟采样定时器（推荐）

仓库文件：

- `deploy/ops/metrics-snapshot.sh`
- `deploy/ops/install-metrics.sh`
- `deploy/systemd/campus-metrics.service`
- `deploy/systemd/campus-metrics.timer`

在 ECS 上（文件已在 `/opt/campus` 或部署包目录）：

```bash
cd /opt/campus
bash install-metrics.sh
```

脚本会：

1. 安装 `/opt/campus/metrics-snapshot.sh`（root 0700）；
2. 创建 `/opt/campus/logs/metrics/`；
3. 安装并启用 `campus-metrics.timer`，每 5 分钟跑一次；
4. 立即跑一次并打印状态。

验证：

```bash
systemctl list-timers campus-metrics.timer --no-pager
tail -n 30 /opt/campus/logs/metrics/metrics-$(date +%F).prom
```

采样文件按天存为 `metrics-YYYY-MM-DD.prom`，保留最近 8 天，超期自动删除。
只保留 HTTP 耗时、Hikari、JVM 内存/GC、CPU、Tomcat 线程与限流计数，避免每天写几百 MB。

如果只想临时采样，不装定时器：

```bash
/opt/campus/metrics-snapshot.sh
```

---

## 3. MySQL 慢查询日志（宝塔面板）

线上 MySQL 8.0 由宝塔管理，改配置不会动应用代码。

### 3.1 打开慢查询

1. 宝塔面板 →「软件商店」→「MySQL 8.0」→「设置」→「配置修改」。
2. 找到 `[mysqld]` 段，在段内追加：

```ini
slow_query_log = ON
slow_query_log_file = /www/server/data/mysql-slow.log
long_query_time = 1
log_queries_not_using_indexes = OFF
min_examined_row_limit = 100
```

说明：

| 配置 | 取值 | 原因 |
| --- | --- | --- |
| `slow_query_log` | `ON` | 开启慢查询 |
| `long_query_time` | `1` | 先抓 ≥1 秒的语句；阈值太低会淹没在正常查询里 |
| `log_queries_not_using_indexes` | `OFF` | 小表全扫很常见，开了只会刷屏；需要时临时打开 |
| `min_examined_row_limit` | `100` | 扫描不足 100 行的语句不记，过滤小表噪声 |

3. 保存后，「服务」→「重启 MySQL」；**选低峰期**（例如 04:00 后），重启约几秒。
4. 宝塔「数据库」→「phpMyAdmin」执行：

```sql
SHOW VARIABLES WHERE Variable_name IN (
  'slow_query_log','slow_query_log_file','long_query_time',
  'log_queries_not_using_indexes','min_examined_row_limit'
);
```

确认 `slow_query_log=ON`，且 `slow_query_log_file` 指向 `/www/server/data/mysql-slow.log`。

如果重启失败：先看宝塔 MySQL 错误日志（`/www/server/data/*.err`），最常见是日志文件路径
无写权限或配置写在了 `[mysqld]` 之外。恢复办法：删掉刚加的 5 行，重启 MySQL。

### 3.2 一周后怎么看

先看频率最高的慢查询：

```bash
tail -n 200 /www/server/data/mysql-slow.log
mysqldumpslow -s t -t 20 /www/server/data/mysql-slow.log
```

`mysqldumpslow` 不在 PATH 时：

```bash
find /www/server/mysql -name mysqldumpslow
```

重点看每条记录的 `Query_time`、`Rows_examined`、`Rows_sent`：

- `Rows_examined` 远大于 `Rows_sent`：索引不匹配或回表多；
- 同一条 SQL 高频出现且 `Query_time` 稳定偏高：优先加/改索引；
- 只在高峰出现：更可能是连接池/锁等待，不要先加索引。

### 3.3 日志切割（避免慢日志无限增长）

宝塔不一定自带慢日志切割。可加 `/etc/logrotate.d/mysql-slow`：

```conf
/www/server/data/mysql-slow.log {
    weekly
    rotate 4
    missingok
    notifempty
    compress
    copytruncate
}
```

`copytruncate` 不需要 FLUSH 权限，适合宝塔托管的 MySQL。

---

## 4. 一周后看哪些数字

| 指标 | 观察点 | 触发进一步分析 |
| --- | --- | --- |
| `http_server_requests_seconds` p95/p99 | 核心接口（`/post`、`/feed`、`/chat`） | p95 持续 >500ms 或 p99 >1s |
| `hikaricp_connections_pending` | 拿不到数据库连接的等待数 | 持续 >0，说明连接池被打满 |
| `tomcat_threads_busy_threads` | 工作线程占用 | 长时间接近 `tomcat_threads_current_threads` |
| `jvm_memory_used_bytes` / GC | 老年代与 Full GC | 老年代长期高位或 Full GC 频繁 |
| `system_cpu_usage` / `process_cpu_usage` | 整机与应用 CPU | 持续 >80% 且与请求量同步 |
| `campus_rate_limit_blocked_total` | 被限流的动作 | 某动作异常高，检查是否误伤或遭刷 |
| MySQL 慢查询 | 次数与 Top SQL | >5 条/分钟或出现固定慢 SQL |

优化顺序建议：先用慢查询定位 SQL/索引 → 再看 N+1 与缓存 → 再看连接池/线程池 →
最后才考虑升配或拆分。不要在没有数据前改索引或加机器。

---

## 5. 回滚

- 应用指标：回滚 jar 即可（旧配置不会请求 `/actuator/prometheus`）。
- 采样定时器：`systemctl disable --now campus-metrics.timer`，再删
  `/etc/systemd/system/campus-metrics.*` 与 `/opt/campus/metrics-snapshot.sh`。
- MySQL 慢查询：从 `my.cnf` 删除新增的 5 行，重启 MySQL。
