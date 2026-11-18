# 性能指标与慢查询采集（2026-11-01）

目标：先用一周真实数据找到瓶颈，再谈优化；不先改索引、不加机器。

本页分三块：

1. 应用指标（随 jar 启用，本次已改代码）
2. 5 分钟采样定时器（可选安装，落盘保留 8 天）
3. MySQL 慢查询日志（线上 MySQL 非宝塔安装，用宝塔「终端」开启；**已开启并持久化**，见 §3）

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

## 3. MySQL 慢查询日志

**安装方式（2026-11-14 确认）：线上 MySQL 不是宝塔「软件商店」安装的。**
面板里「配置文件」页显示空白，点「慢日志」提示「此功能暂时只支持面板软件商店安装的mysql！」，
「数据库」页的库列表为空（面板只显示自己登记的库），点「phpMyAdmin」提示依赖 MySQL 未安装、
装不上。这几处都不影响数据库本身，但**面板这条路走不通，只能用左侧「终端」**。
慢查询因此只在命令行里开（方案 A / 方案 B）；
日志路径也不能默认写 `/www/server/data/mysql-slow.log`，以 `SHOW VARIABLES` 的输出为准。

> **当前状态（截至 2026-11-14 核对）：已开启并已持久化。**
> 2026-11-14 用宝塔「终端」开启：`slow_query_log=ON`、`long_query_time=1`、
> `min_examined_row_limit=100`、`log_queries_not_using_indexes=OFF`，`log_output=FILE`，
> 日志在 `/var/lib/mysql/<主机名>-slow.log`（以 `slow_query_log_file` 的值为准）。
> 同一组值随后用 `SET PERSIST` 写入 `mysqld-auto.cnf`：`performance_schema.persisted_variables`
> 中已确认 4 项（`slow_query_log=ON`、`long_query_time=1.000000`、`min_examined_row_limit=100`、
> `log_queries_not_using_indexes=OFF`），**MySQL 重启后仍为 ON**。
> 2026-11-14 22:45–22:46 实测写入成功：root 执行两次
> `SET SESSION long_query_time=1; SET SESSION min_examined_row_limit=0; SELECT SLEEP(1.5);`，
> `Slow_queries` 计数 1→2，日志各追加一条 `# Query_time: 1.50...` 与 `SELECT SLEEP(1.5);`
> （文件 387 字节），确认「判定为慢查询 → 写文件」链路正常。
> 仓库的 `backend/src/main/resources` 与 `deploy/` 仍未设置 `slow_query_log`，线上状态由 `SET PERSIST` 维持。
>
> 状态以 §3.4 的核对结果为准：如果核对显示已关闭，把本段改回未开启并同步 §3.4，
> 不要让文档停留在旧状态。

### 3.1 打开慢查询

#### 方案 A：终端里即时开启（零改配置、零重启，先做这个）

宝塔左侧「终端」，整段粘贴执行。脚本先试 root 免密，不行再用 `/opt/campus/.env` 里的应用账号；
成功后打开慢查询并打印当前状态：

```bash
set -u
MYSQL_BIN="$(command -v mysql || true)"
if [ -z "$MYSQL_BIN" ]; then
  for c in /usr/bin/mysql /usr/local/mysql/bin/mysql /www/server/mysql/bin/mysql; do
    [ -x "$c" ] && MYSQL_BIN="$c" && break
  done
fi
if [ -z "$MYSQL_BIN" ]; then
  echo "[失败] 宿主机上找不到 mysql 客户端，可能是 Docker 安装的 MySQL。当前进程："
  ps -ef | grep -v grep | grep mysqld
  exit 1
fi

ARGS=()
if "$MYSQL_BIN" -e "SELECT 1" >/dev/null 2>&1; then
  echo "[连接] root 免密可用"
else
  ENVF="${ENVF:-/opt/campus/.env}"
  if [ ! -f "$ENVF" ]; then
    echo "[失败] root 免密不可用，且缺少 $ENVF"
    exit 1
  fi
  set -a
  . "$ENVF"
  set +a
  export MYSQL_PWD="${MYSQL_PASSWORD:-}"
  ARGS=(-h"${MYSQL_HOST:-127.0.0.1}" -P"${MYSQL_PORT:-3306}" -u"${MYSQL_USER:-root}")
  if ! "$MYSQL_BIN" "${ARGS[@]}" -e "SELECT 1" >/dev/null 2>&1; then
    echo "[失败] root 免密与应用账号 ${MYSQL_USER:-?} 都连不上"
    exit 1
  fi
  echo "[连接] 应用账号 ${MYSQL_USER:-?}"
fi

if "$MYSQL_BIN" "${ARGS[@]}" -e "SET GLOBAL slow_query_log=ON, long_query_time=1, log_queries_not_using_indexes=OFF, min_examined_row_limit=100;"; then
  echo "[结果] 已开启"
else
  echo "[结果] 开启失败；若报 1227，说明当前账号没有修改全局变量的权限，需要在终端里以 root 登录后再执行"
fi

"$MYSQL_BIN" "${ARGS[@]}" -e "SHOW GLOBAL VARIABLES WHERE Variable_name IN ('slow_query_log','slow_query_log_file','log_output','long_query_time','log_queries_not_using_indexes','min_examined_row_limit');"
```

期望输出：`[连接] …`、`[结果] 已开启`，最后表里 `slow_query_log` 为 `ON`。

读结果：

- `slow_query_log = ON` → 已在记录，立即生效，不用重启；
- `slow_query_log_file` 的值就是日志实际落盘位置，**以这个值为准**，不要照抄本文档里的历史路径；
- `log_output = FILE` → 记录写文件；若是 `TABLE`，记录在 `mysql.slow_log` 表里，查法见 §3.2。

> **局限：`SET GLOBAL` 只对当前运行期有效，MySQL 重启或服务器重启后回到关闭。**
> 线上 2026-11-14 已在此之后补做方案 B（`SET PERSIST`），重启不再丢失；
> 若在别的环境只做了方案 A，需要长期保留就补做方案 B。

各参数取值理由：

| 配置 | 取值 | 原因 |
| --- | --- | --- |
| `slow_query_log` | `ON` | 开启慢查询 |
| `long_query_time` | `1` | 先抓 ≥1 秒的语句；阈值太低会淹没在正常查询里 |
| `log_queries_not_using_indexes` | `OFF` | 小表全扫很常见，开了只会刷屏；需要时临时打开 |
| `min_examined_row_limit` | `100` | 扫描不足 100 行的语句不记，过滤小表噪声 |

#### 方案 B：`SET PERSIST` 持久化（免改配置、免重启，重启后仍生效）

方案 A 的设置在 MySQL 重启后失效。`SET PERSIST` 把同一组值写进 MySQL 自己的
`mysqld-auto.cnf`，立即生效、下次启动自动加载，不需要改 `my.cnf`、不需要重启：

```bash
mysql -e "SET PERSIST slow_query_log=ON, long_query_time=1, log_queries_not_using_indexes=OFF, min_examined_row_limit=100;"
mysql -e "SELECT VARIABLE_NAME, VARIABLE_VALUE FROM performance_schema.persisted_variables ORDER BY VARIABLE_NAME;"
```

第二条应列出上述 4 个变量。撤销用逐条 `RESET PERSIST <变量名>;`（不带变量名会清空全部持久化项）。

注意：持久化项在启动时**覆盖** `my.cnf` 里的同名配置；以后若改用 my.cnf 管理，先 `RESET PERSIST`
再去改文件。本次不持久化 `slow_query_log_file`，重启后仍用默认的 `/var/lib/mysql/<主机名>-slow.log`，
路径不变。

#### 方案 C：写进 my.cnf（持久化，需要低峰期重启）

先查 mysqld 真正读取哪个配置文件，不要按路径猜：

```bash
ps -ef | grep -v grep | grep mysqld
mysqld --verbose --help 2>/dev/null | grep -A1 "Default options are read"
```

- 第一条输出里带 `--defaults-file=...` 时，那就是实际配置文件；
- 不带时看第二条打印的搜索顺序（例如 `/etc/my.cnf /etc/mysql/my.cnf ~/.my.cnf`），
  取其中**第一个存在**的文件。

备份后追加（追加时带独立 `[mysqld]` 段头，保证写在 `[mysqld]` 组内、且位于文件末尾覆盖前面的同名项）：

```bash
CNF=/etc/mysql/my.cnf        # 换成上面查到的实际文件
cp -a "$CNF" "$CNF.bak_$(date +%Y%m%d%H%M)"
cat >> "$CNF" <<'EOF'

[mysqld]
slow_query_log = ON
slow_query_log_file = mysql-slow.log
long_query_time = 1
log_queries_not_using_indexes = OFF
min_examined_row_limit = 100
EOF
```

`slow_query_log_file` 写相对文件名时落在 MySQL 数据目录（`datadir`）下，不用猜绝对路径，
也没有目录权限问题；要写绝对路径时，目录必须存在且 mysql 用户可写。

低峰期重启前先确认服务单元名：

```bash
systemctl list-units --type=service --state=running | grep -i mysql
```

输出形如 `mysqld.service` 或 `mysql.service`，用输出里的名字重启：

```bash
systemctl restart mysqld
```

重启失败的回滚（恢复备份再重启，`$CNF.bak_...` 用上一步生成的实际文件名）：

```bash
cp -a "$CNF.bak_202601010000" "$CNF"
systemctl restart mysqld
```

改完用 §3.4 核对：`slow_query_log=ON`，且**重启一次后仍然是 ON**，才算持久化成功。
若重启后仍是 `OFF`，说明搜索顺序里更靠后的配置文件覆盖了它，按同样顺序检查后面几个文件里的
`slow_query_log`。

### 3.2 一周后怎么看

日志路径用 §3.1 里 `slow_query_log_file` 查到的实际值：

```bash
SLOWLOG=/var/lib/mysql/xxx-slow.log   # 换成实际路径；值是相对文件名时拼在 datadir 后面
tail -n 200 "$SLOWLOG"
mysqldumpslow -s t -t 20 "$SLOWLOG"
```

路径不要照抄示例：`slow_query_log_file` 不以 `/` 开头时，真实路径是 `datadir` + 该文件名。
文件名由主机名生成，大小写和字符容易抄错；最稳妥用通配符，让 shell 自己匹配：

```bash
ls -l /var/lib/mysql/*-slow.log
tail -n 200 /var/lib/mysql/*-slow.log
```

拿不准时查 MySQL 实际打开的文件（`performance_schema` 那条查询见下面的自测段），
里面带 `slow` 的那一行就是真实路径。

`mysql` / `mysqldumpslow` 不在 PATH 时：

```bash
command -v mysql mysqldumpslow
find /usr /opt /www -name mysqldumpslow 2>/dev/null
```

`log_output=TABLE` 时没有文件，用终端查（先载入库账号，再查表）：

```bash
set -a; . /opt/campus/.env; set +a
MYSQL_PWD="$MYSQL_PASSWORD" mysql -h"${MYSQL_HOST:-127.0.0.1}" -P"${MYSQL_PORT:-3306}" -u"$MYSQL_USER" \
  -e "SELECT start_time, query_time, rows_examined, rows_sent, LEFT(sql_text,200) AS sql_text FROM mysql.slow_log ORDER BY query_time DESC LIMIT 50;"
```

应用账号可能没有 `mysql.slow_log` 的读取权限，报 1142/1145 时改用 root 执行。

重点看每条记录的 `Query_time`、`Rows_examined`、`Rows_sent`：

- `Rows_examined` 远大于 `Rows_sent`：索引不匹配或回表多；
- 同一条 SQL 高频出现且 `Query_time` 稳定偏高：优先加/改索引；
- 只在高峰出现：更可能是连接池/锁等待，不要先加索引。

**没人访问时日志为空，属正常。** 慢日志只记录真实执行过、且达到阈值的 SQL；
低峰期没人操作，就没有业务查询，自然没有记录——这不代表开关没生效，也不代表性能没问题，
只是还没有被触发。少量后台任务（清理、对账、健康检查）也会查库，但通常不会超过 1 秒。

想确认采集链路真的通，可在服务器终端用 **root** 做一次无害自测（只放宽当前会话的阈值，
跑一条 1.5 秒的空查询，不读写任何业务表）。**判定标准是 `Slow_queries` 计数涨没涨**：
文件是否存在、有没有内容还受 `log_output` 和路径影响，只有计数是「有没有被判定为慢查询」的权威信号：

```bash
mysql -N -B -e "SHOW GLOBAL STATUS LIKE 'Slow_queries';"
mysql -e "SET SESSION long_query_time=1; SET SESSION min_examined_row_limit=0; SELECT SLEEP(1.5);"
mysql -N -B -e "SHOW GLOBAL STATUS LIKE 'Slow_queries';"
mysql -t -e "SELECT @@global.log_output AS log_output, @@datadir AS datadir,
  @@global.slow_query_log_file AS slow_log_file,
  (SELECT GROUP_CONCAT(FILE_NAME SEPARATOR '\n') FROM performance_schema.file_instances
   WHERE FILE_NAME LIKE '%slow%') AS opened_files;"
```

读结果：

- 两次计数从 X 到 X+1 → 已被判定为慢查询，采集链路通；
- 计数不变 → 没被判定（阈值、会话继承或开关问题），先按 §3.4 方式 A 核对变量；
- 报 `ERROR 1227`（需要 `SYSTEM_VARIABLES_ADMIN` / `SESSION_VARIABLES_ADMIN`）或
  `ERROR 1142`（`file_instances` 拒绝访问）→ 用的是应用账号，换成 root 执行；
- `log_output=TABLE` → 记录在 `mysql.slow_log` 表里，本来就没有日志文件（查法见本节开头）；
- `log_output=FILE` → **日志文件在开启那一刻就已创建**，没有慢 SQL 时只有几行文件头（约 181 字节），
  所以「开关是 ON 但文件不存在」本身就不正常；`opened_files` 里带 `slow` 的行就是 MySQL
  实际打开的文件。文件名来自主机名，直接 `tail -n 20 /var/lib/mysql/*-slow.log` 即可，不要手抄路径。

`SET SESSION` 与 `SELECT SLEEP(1.5)` 必须在同一条 `mysql -e` 里执行，否则会话设置不生效。
自测必须用 root：应用账号（`campus`）没有 `SESSION_VARIABLES_ADMIN`，`SET SESSION` 会直接报 1227；
也没有 `performance_schema.file_instances` 的读取权限，会报 1142。

### 3.3 日志切割（避免慢日志无限增长）

宝塔不一定自带慢日志切割，这台 MySQL 也不是宝塔装的，切割要自己加。
日志文件名由主机名生成（见 §3.1），用通配符写，避免手抄错大小写，新建 `/etc/logrotate.d/mysql-slow`：

```conf
/var/lib/mysql/*-slow.log {
    weekly
    rotate 4
    missingok
    notifempty
    compress
    copytruncate
}
```

`copytruncate` 不需要 FLUSH 权限，不依赖 MySQL 的安装方式。

`log_output=TABLE` 时没有文件要切割，定期在终端执行
`mysql ... -e "TRUNCATE TABLE mysql.slow_log;"` 即可（账号载入方式同 §3.2）。

### 3.4 核对当前状态（改完或怀疑文档过期时先跑这个）

**方式 A：终端一条命令（不依赖仓库文件和面板插件，随时可跑）**

```bash
set -a; . /opt/campus/.env; set +a
MYSQL_PWD="$MYSQL_PASSWORD" mysql -h"${MYSQL_HOST:-127.0.0.1}" -P"${MYSQL_PORT:-3306}" -u"$MYSQL_USER" \
  -e "SHOW GLOBAL VARIABLES WHERE Variable_name IN ('slow_query_log','slow_query_log_file','log_output','long_query_time','log_queries_not_using_indexes','min_examined_row_limit');"
```

判读：

- `slow_query_log = OFF` → 未开启，本文档「当前状态」一栏应写未开启；
- `slow_query_log = ON` → 已生效，日志位置看 `slow_query_log_file` 的当前值（或 `log_output=TABLE` 时看 `mysql.slow_log` 表）；
- `slow_query_log = ON`、`log_output=FILE` 但按 `slow_query_log_file` 找不到文件 → 文件在开启那一刻
  就应存在（没有慢 SQL 时只有约 181 字节文件头），找不到说明实际路径不同：相对文件名落在 `datadir`
  下，用 `performance_schema.file_instances` 里带 `slow` 的行看真实路径；也可能是打开失败
  （查 MySQL error log 里的 `Could not open` 与目录写权限）。`log_output=TABLE` 时没有文件，属正常；
- 只做过方案 A 时，MySQL 重启后本项会变回 `OFF`，属预期，需要长期保留就做方案 B；
- 再查 `SELECT VARIABLE_NAME, VARIABLE_VALUE FROM performance_schema.persisted_variables;`：
  里面出现 `slow_query_log` 说明已用方案 B 持久化，重启后仍为 `ON`；没有则只是当前运行期开启。
  线上 2026-11-14 核对结果为 4 项俱全（`slow_query_log=ON`、`long_query_time=1.000000`、
  `min_examined_row_limit=100`、`log_queries_not_using_indexes=OFF`），已持久化。

**方式 B：部署包内脚本（一条命令，带退出码，适合巡检）**

```bash
cd /opt/campus
bash check-mysql-slowlog.sh
```

- 退出码 `0`：已开启，同时打印阈值、日志位置与持久化状态（`SET PERSIST` 共几项）；
- 退出码 `2`：未开启；
- 退出码 `1`：检查失败（缺 `.env`、连不上数据库、找不到 mysql 客户端）。

脚本从 `/opt/campus/.env` 读 `MYSQL_*`，只执行 `SHOW GLOBAL VARIABLES` 与只读查询
`performance_schema.persisted_variables`，不改任何配置；查不到持久化表时会提示用 root 复查，不误报成「未持久化」。
r7.18 起的部署包会把它安装到 `/opt/campus/check-mysql-slowlog.sh`；旧包没有该脚本时用方式 A。
**线上 2026-11-14 部署的 r7.18 装的是增强前版本**：只报开关、阈值与日志路径，退出码一致，
不打印 `log_output` 与持久化项数；增强版已入仓库，下次部署任意包时由 `deploy.sh` 覆盖安装。
线上旧脚本跑出「已开启」即说明当前已生效；MySQL 重启后再跑仍是「已开启」，就说明持久化生效。
要单独看持久化项，用方式 A 查 `performance_schema.persisted_variables`。

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
| MySQL 慢查询 | 次数与 Top SQL（2026-11-14 已开启并持久化，累积一周后再看；核对见 §3.4） | >5 条/分钟或出现固定慢 SQL |

优化顺序建议：先用慢查询定位 SQL/索引 → 再看 N+1 与缓存 → 再看连接池/线程池 →
最后才考虑升配或拆分。不要在没有数据前改索引或加机器。

---

## 5. 回滚

- 应用指标：回滚 jar 即可（旧配置不会请求 `/actuator/prometheus`）。
- 采样定时器：`systemctl disable --now campus-metrics.timer`，再删
  `/etc/systemd/system/campus-metrics.*` 与 `/opt/campus/metrics-snapshot.sh`。
- MySQL 慢查询：只做过方案 A（`SET GLOBAL`）的，重启 MySQL 即恢复关闭，无需改配置；
  做过方案 B（`SET PERSIST`）的，逐条 `RESET PERSIST slow_query_log;` /
  `RESET PERSIST long_query_time;` / `RESET PERSIST log_queries_not_using_indexes;` /
  `RESET PERSIST min_examined_row_limit;` 再重启；
  做过方案 C 的，恢复 `my.cnf` 备份（`cp -a "$CNF.bak_时间戳" "$CNF"`）后重启 MySQL。
  改完用 §3.4 复核，并同步更新本文档开头的「当前状态」。
