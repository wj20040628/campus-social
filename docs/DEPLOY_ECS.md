# 友声 · 校园生活社区 —— ECS 从零部署清单

适用对象：阿里云 ECS 华东1(杭州)G，`ecs.e-c1m1.large`（2核2G，Ubuntu 22.04，3Mbps）。
公网 `120.26.4.66`，私网 `192.168.25.254`。域名 `yousheng8.cn`（ICP 备案进行中）。

本文档只讲「怎么把项目跑在服务器上 + 之后怎么迭代」，不重复讲代码逻辑。

---

## 0. 两条铁律

1. **服务器只跑产物，不写代码。** 所有源码改动都在你这台 Windows 开发机完成、验证、提交，再打包发上去。绝不在 ECS 上 vim 改 Java。
2. **配置全走环境变量，不写死在代码里。** 改功能 = 改 `src`；改运行环境（换桶/换密钥/开直传）= 改 systemd 的 `Environment=`。两条线分开。

---

## 1. 备案前 vs 备案后（两种形态）

ICP 备案卡的是「域名 DNS 解析到这台 ECS + 开放 80/443」。在此之前：

| 项目 | 备案前（现在就能做） | 备案过审后 |
|------|----------------------|------------|
| 访问方式 | 公网 IP + 非标端口，如 `http://120.26.4.66:8088` | 域名 `https://yousheng8.cn`（80/443 + SSL） |
| 前端入口 | Nginx 监听 8088 | Nginx 监听 80/443 |
| OSS 直传 | **关**（`OSS_DIRECT_UPLOAD_ENABLED=false`） | 开（true） |
| 邮件发信 | 已通（走 ECS RAM 角色，与备案无关） | 不变 |
| 图片读写 | 后端 `POST /upload` 服务端兜底写 OSS，读走 presign 签名 URL | 同左；开直传后前端直传更快 |

> 直传依赖 OSS 回调到「已备案」域名 `yousheng8.cn`，备案中该回调不通，直传会被 OSS 判失败。服务端兜底路径不依赖回调域名，备案中可用。
> 所以：现在就能把完整功能部署联调，只是用 IP+端口访问、直传暂缓。备案一过审，只改 Nginx 端口 + 把直传开关拨 true，无需改代码。

---

## 2. ECS 软件安装（一次性）

SSH 登进去：`ssh root@120.26.4.66`

```bash
# 基础
apt update && apt -y upgrade
apt -y install openjdk-17-jre-headless nginx

# MySQL 8.0（LTS，别用 9.0；connector 已是 8.4.0）
apt -y install mysql-server
# 初始化安全：设 root 强密码、禁匿名、禁远程 root
mysql_secure_installation

# Redis >= 6.0（Ubuntu 22.04 默认 6.0.16，带 GETDEL，够用；本机 5.0 缺这个命令是开发机特例）
apt -y install redis-server
# 设密码：编辑 /etc/redis/redis.conf -> requirepass <强密码>，然后 systemctl restart redis

# 防火墙：只开 SSH + Nginx 端口，后端 8080 不对外
ufw allow 22
ufw allow 8088        # 备案前
# ufw allow 80 && ufw allow 443   # 备案后取消注释
ufw enable
```

建库（字符集与本地一致）：

```bash
mysql -uroot -p
CREATE DATABASE campus_social_v2
  CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
-- 建一个专用账号给应用（别用 root 直连应用）
CREATE USER 'campus'@'localhost' IDENTIFIED BY '换一个强密码';
GRANT ALL PRIVILEGES ON campus_social_v2.* TO 'campus'@'localhost';
FLUSH PRIVILEGES;
```

导入表结构（从仓库 `sql/schema.sql`，已含全部表）：

```bash
mysql -ucampus -p campus_social_v2 < sql/schema.sql
# 若有增量 migration（见第 7 节），一并执行
```

敏感词库与预留圈名是**两张必须灌数据的表**，`schema.sql` 只建表不插数据，跳过这两步等于功能空转：

```bash
# 1) 敏感词库 154 条 —— DFA 是 LLM 关闭状态下唯一的自动审核闸门，
#    词库为空时发布/改名全部直接放过，审核台只剩人工队列。
mysql -ucampus -p campus_social_v2 < sql/migration_sensitive_words.sql

# 2) 预留官方圈名 38 条 —— CircleServiceImpl 的官方名保护读这张表，
#    空表意味着任何人都能建「黄河交通学院教务处」冒充校方。
#    幂等（uk_rname + INSERT IGNORE），可重复执行；后续增删走后台「预留圈名」页。
mysql -ucampus -p campus_social_v2 < sql/migration_reserved_circle_names.sql

# 导入后自检，两条都应 > 0：
#   SELECT COUNT(*) FROM sensitive_word;
#   SELECT COUNT(*) FROM reserved_circle_name;
```

**生产库不要执行 `sql/seed_data.sql`**：那是答辩演示数据，会灌进 `admin@campus.test / admin123`（口令与哈希都在仓库里，等于公开后门）和 bob/carol/dave/erin 四个演示号。

管理员不要靠种子数据造，按下面两步产生第一个管理员账号（本表 `role=1` 即可进审核后台，全仓没有管理员引导接口，只能这样起）：

```bash
# 1) 用真实邮箱在页面上正常注册一个账号（自己设强口令，走验证码通道）
# 2) 把这个账号提成管理员。先确认 id 没错再执行：
mysql -ucampus -p campus_social_v2 -e \
  "SELECT id,username,nickname,role FROM user ORDER BY id DESC LIMIT 5"
mysql -ucampus -p campus_social_v2 -e \
  "UPDATE user SET role = 1 WHERE id = <上一步确认的 id> AND username = '<你的注册邮箱>'"
```

`AND username = ...` 是防手滑：只按 id 更新，万一 id 记错就把别人的号提成管理员了。两个条件同时命中才生效，影响行数应为 1。

---

## 3. 目录规划（ECS 上）

```
/opt/app/                     应用目录
  campus-social.jar           后端产物（保留上一个版本便于回滚）
  campus-social.jar.bak
  run.log                     后端日志
/var/www/campus/              Nginx 前端根目录（放 frontend/dist 内容）
/var/www/campus-bak/          上一份前端（回滚用）
/etc/systemd/system/campus.service   后端服务单元
/etc/nginx/conf.d/campus.conf        Nginx 站点配置
```

建目录：

```bash
mkdir -p /opt/app /var/www/campus /var/www/campus-bak
```

---

## 4. 本机构建（在你 Windows 开发机，不在 ECS）

后端打 jar：

```bash
cd F:\校园社交\backend
mvn package -DskipTests
# 产出 backend/target/campus-social-*.jar
```

前端打 dist（注意：前端连的是相对路径 /api，由 Nginx 反代，所以 build 时无需改 baseURL）：

```bash
cd F:\校园社交\frontend
npm install
npm run build
# 产出 frontend/dist/  （index.html + assets/）
```

只传产物：

```bash
scp backend/target/campus-social-*.jar root@120.26.4.66:/opt/app/
scp -r frontend/dist/* root@120.26.4.66:/var/www/campus/
```

---

## 5. 后端 systemd 服务（含完整 env 清单）

写 `/etc/systemd/system/campus.service`：

```ini
[Unit]
Description=Campus Social Backend
After=network.target mysql.service redis.service

[Service]
Type=simple
WorkingDirectory=/opt/app
# ---- JVM 内存参数：2核2G 机器的关键 ----
# -Xms256m -Xmx384m  堆的初始值和上限都压到 384m。
#   为什么不给 512m：堆只是 JVM 进程内存的一部分，堆外还有
#   Metaspace（约 110m）、线程栈、JIT 编译代码缓存、GC 自身结构。
#   堆给 512m 时，整个 java 进程实际占的物理内存（RSS）约 950m，
#   加上 MySQL(~600m) + Redis(~150m) + 系统(~200m) 就超 2G，只能靠 swap，
#   一旦开始换页，接口延迟从几十毫秒涨到几百毫秒，表现为「没人访问也很卡」。
# -XX:MaxMetaspaceSize=192m  给 Metaspace 设上限。
#   不限的话它是按需增长、只增不减，跑久了会慢慢吃满剩余内存；
#   设了上限会在超限时抛 OutOfMemoryError: Metaspace 并退出，
#   由 systemd 的 Restart=on-failure 拉起，比整机 swap 卡死好排查。
# 线程数上限不在这里配，已写进 application.yml（server.tomcat.threads.max=100），
# 改那一处即可，不必重启机器。
ExecStart=/usr/bin/java -Xms256m -Xmx384m -XX:MaxMetaspaceSize=192m -jar /opt/app/campus-social.jar
# 关停时给点时间让 Flyway/连接池优雅退出
TimeoutStopSec=30
Restart=on-failure
RestartSec=5

# ---------- 环境变量（生产全部在这里，不进代码）----------
# JWT：各 >= 64 字节随机串，access/refresh 必须不同
Environment=JWT_ACCESS_SECRET=填一段至少64字节的随机十六进制
Environment=JWT_REFRESH_SECRET=填另一段至少64字节的随机十六进制

# 数据库（用第 2 节建的 campus 账号）
Environment=MYSQL_HOST=127.0.0.1
Environment=MYSQL_PORT=3306
Environment=MYSQL_USER=campus
Environment=MYSQL_PASSWORD=刚才建的强密码

# Redis（填第 2 节 requirepass 的密码）
Environment=REDIS_HOST=127.0.0.1
Environment=REDIS_PORT=6379
Environment=REDIS_PASSWORD=Redis强密码
Environment=REDIS_DB=0

# OSS（四 AK 必填；直传开关备案后才拨 true）
Environment=OSS_BUCKET=yousheng-uploads
Environment=OSS_REGION=cn-hangzhou
Environment=OSS_ENDPOINT=oss-cn-hangzhou.aliyuncs.com
Environment=OSS_INTERNAL_ENDPOINT=oss-cn-hangzhou-internal.aliyuncs.com
Environment=OSS_STS_ENDPOINT=sts.cn-hangzhou.aliyuncs.com
Environment=OSS_ROLE_ARN=acs:ram::1930391355152122:role/oss-uploader-role
Environment=OSS_CALLBACK_URL=https://yousheng8.cn/upload/oss-callback
Environment=OSS_CALLBACK_KEY=填32位hex回调验签密钥
Environment=OSS_DIRECT_UPLOAD_ENABLED=false
Environment=OSS_STS_ACCESS_KEY_ID=填STS固定AK
Environment=OSS_STS_ACCESS_KEY_SECRET=填STS固定SK
Environment=OSS_READ_ACCESS_KEY_ID=填只读AK
Environment=OSS_READ_ACCESS_KEY_SECRET=填只读SK

# 邮件（ECS 已绑 mail RAM 角色，走 ecs_ram_role，磁盘/env 无 AK）
Environment=APP_MESSAGE_GATEWAY=aliyun
Environment=ALIYUN_DM_CREDENTIAL_TYPE=ecs_ram_role
Environment=ALIYUN_DM_TEMPLATE_ID=445523
Environment=ALIYUN_DM_FROM_ADDRESS=system@yousheng8.cn

# 校园参数
Environment=CAMPUS_SCHOOL_NAME=黄河交通学院
Environment=CAMPUS_SCHOOL_ID=1
Environment=CAMPUS_EMAIL_DOMAIN_WHITELIST=填校园邮箱域名,多个逗号分隔

# AI 机审：生产默认关，转人工兜底（不填 LLM_API_KEY 也不崩）
Environment=LLM_ENABLED=false

# 跨域：同源部署（Nginx 同时 serve 前端+反代），可留空
# Environment=CORS_ALLOWED_ORIGINS=https://yousheng8.cn

[Install]
WantedBy=multi-user.target
```

> 密钥生成示例（任选其一，在 ECS 或本机跑，结果填进上面）：
> `openssl rand -hex 48` 得到 96 字节十六进制，够 JWT 用；
> `openssl rand -hex 16` 得到 32 字节，够 OSS_CALLBACK_KEY。

启用：

```bash
systemctl daemon-reload
systemctl enable --now campus
journalctl -u campus -f    # 看启动日志，确认无 IllegalStateException（JWT 长度校验）
```

---

## 6. Nginx 配置（同源，免跨域）

写 `/etc/nginx/conf.d/campus.conf`（备案前监听 8088，备案后改 80/443 + SSL）：

```nginx
# 备案前
# ---- API 限速(2026-10-25,防爬):游客可读详情接口放行后,未认证 GET
# 无限流可被拖库。按 IP 20r/s + burst 40,正常页面加载(并发 10-20 请求)
# 不误伤,持续超速收 429。conf.d 被 include 进 http,zone 声明写在 server 外即 http 级。
limit_req_zone $binary_remote_addr zone=api_limit:10m rate=20r/s;

server {
    listen 8088;
    server_name 120.26.4.66;

    # 前端静态资源
    root /var/www/campus;
    index index.html;
    # Vue 路由 history 模式：未知路径回落 index.html
    location / {
        try_files $uri $uri/ /index.html;
    }

    # 后端 API 反代（只暴露这几个前缀，8080 不对公网）
    location /api/ {
        limit_req zone=api_limit burst=40 nodelay;
        limit_req_status 429;
        proxy_pass http://127.0.0.1:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    }
    location /upload/ {
        proxy_pass http://127.0.0.1:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
    }
    # WebSocket（聊天）必须带 Upgrade 头
    location /ws/ {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_set_header Host $host;
        proxy_read_timeout 3600s;
    }
}
```

```bash
nginx -t && systemctl reload nginx
```

备案过审后：把 `listen 8088` 改成 `listen 80; listen 443 ssl;`，加 `ssl_certificate` / `ssl_certificate_key`（在阿里云申请免费 DV 证书），并把 `OSS_DIRECT_UPLOAD_ENABLED` 拨 `true` 后 `systemctl restart campus`。前端 dist 无需重打。

---

## 7. 数据库变更（加功能改表时）

加字段/加表，走 SQL 脚本，不当场手敲：

1. 本机 MySQL 改好，同时把变更写成 `sql/migration_YYYYMMDD.sql`（建表/加列/加索引）。
2. 部署时把脚本传 ECS 执行：
   ```bash
   scp sql/migration_xxx.sql root@120.26.4.66:/opt/app/
   ssh root@120.26.4.66 "mysql -ucampus -p campus_social_v2 < /opt/app/migration_xxx.sql"
   ```
3. 后端代码用新结构。

仓库里已有的增量脚本（按序执行）：`sql/migration_f11_joined_circle_count.sql`、`sql/migration_f12_school_major.sql`、`sql/migration_f12_seed_school_major.sql`、`docs/sql/migration_v2.sql`。首次部署若 `schema.sql` 已含这些，可跳过；不确定就先 `show tables` 比对。

---

## 8. 迭代部署（改功能/加功能后）

闭环：

```bash
# 1. 本机改代码，本地 8080 + 5173 联调验证通过

# 2. 提交进仓库
git add -A && git commit -m "说明这次改了啥" && git push origin master

# 3. 本机重新构建（见第 4 节）
mvn package -DskipTests
cd ../frontend && npm run build

# 4. 传产物（先备份线上旧版）
ssh root@120.26.4.66 "cp /opt/app/campus-social.jar /opt/app/campus-social.jar.bak; cp -r /var/www/campus /var/www/campus-bak"
scp backend/target/campus-social-*.jar root@120.26.4.66:/opt/app/campus-social.jar
scp -r frontend/dist/* root@120.26.4.66:/var/www/campus/

# 5. 重启后端（前端覆盖即生效，无需重启 nginx；若担心缓存就 nginx -s reload）
systemctl restart campus
journalctl -u campus -f   # 看启动有无报错

# 6. 若这次改了表，执行第 7 节的 migration
```

验证：用手机/换号点一遍改动涉及的功能（尤其发帖、聊天、头像这几个之前踩过坑的路径）。

---

## 9. 回滚

```bash
# 后端回退
ssh root@120.26.4.66
systemctl stop campus
cp /opt/app/campus-social.jar.bak /opt/app/campus-social.jar
systemctl start campus

# 前端回退
rm -rf /var/www/campus && cp -r /var/www/campus-bak /var/www/campus
nginx -s reload
```

数据在 MySQL 里，与 jar/dist 分离，回滚不影响用户数据。若回滚涉及「表结构也回退」，需手动写反向 migration（谨慎，通常不做，改用新 migration 修补）。

---

## 10. 2核2G 容量调优（必做）

这台机器的瓶颈是**内存**，不是 CPU、也不是带宽。2G 总内存要同时养活四样东西：

| 组件 | 常驻内存（调优前） | 调优后 | 说明 |
|------|-------------------|--------|------|
| 系统 + Nginx + journald | 约 210M | 约 210M | 有下限，压不动 |
| MySQL 8.0 | 约 620M | 约 400M | buffer pool + 每连接缓冲 + performance_schema |
| Redis | 约 150M | 约 60M | 实际数据量很小，占用主要来自默认不设上限 |
| JVM（`-Xmx512m`） | 约 950M | 约 750M | 堆 512M→384M，堆外还有 Metaspace 等 |
| **合计** | **约 1930M** | **约 1420M** | 调优前几乎没有 page cache，MySQL 反复读盘 |

调优前的状态不是「慢」，是「不稳」：剩余内存不足 120M，MySQL 拿不到文件缓存，一旦并发上来就触发 swap，延迟从几十毫秒跳到几百毫秒。下面四步按顺序做。

### 10.1 JVM 堆

已在第 5 节的 systemd `ExecStart` 里改成 `-Xms256m -Xmx384m -XX:MaxMetaspaceSize=192m`，改完执行：

```bash
systemctl daemon-reload
systemctl restart campus
```

### 10.2 MySQL

编辑 `/etc/mysql/mysql.conf.d/mysqld.cnf`，在 `[mysqld]` 段落下追加：

```ini
# 缓冲池：缓存表和索引的数据页。2G 机器给 128M 是上限，再大就把系统挤进 swap。
# 单实例部署必须配 innodb_buffer_pool_instances = 1，
# 否则 MySQL 会把 128M 平均切成多份，每份都很小，命中率反而下降。
innodb_buffer_pool_size = 128M
innodb_buffer_pool_instances = 1

# performance_schema 是性能诊断库，默认开启要吃掉约 150-200M 内存。
# 关掉后仍可用 SHOW STATUS / SHOW PROCESSLIST 看基础指标，
# 只是查不到 performance_schema 里的细粒度等待事件。
# 2G 机器上这笔内存换给 buffer pool 更划算；以后换 4G/8G 再打开。
performance_schema = OFF

# 连接数上限。连接池只开 8 条，50 是留给人肉排查和备份脚本的余量。
# 每条 MySQL 连接在服务端要占约 300-500K，50 条约 20M。
max_connections = 50
```

```bash
systemctl restart mysql
mysql -ucampus -p -e "SHOW VARIABLES LIKE 'innodb_buffer_pool_size'; SHOW VARIABLES LIKE 'performance_schema';"
```

### 10.3 Redis

Redis 里存的是**验证码、封禁计划、未读数**，不是可随时丢失的页面缓存。因此不设淘汰策略，改为「限额 + 满了就报错」：

```bash
redis-cli -a '<你的Redis密码>' CONFIG SET maxmemory 128mb
redis-cli -a '<你的Redis密码>' CONFIG SET maxmemory-policy noeviction
# 持久化到配置文件，否则重启失效
```

```conf
# 追加到 /etc/redis/redis.conf
maxmemory 128mb
maxmemory-policy noeviction
```

> 为什么不用 `allkeys-lru`：LRU 会按「最久未用」淘汰任意 key，验证码被淘汰只是让用户重发一次，但**封禁计划被淘汰就意味着这个用户永远不会自动解封**——这是数据错误，不是缓存穿透。`noeviction` 在内存写满时返回错误，问题会暴露在日志里而不是悄悄丢数据。实际数据量远低于 128M，这个上限只是保险丝。

### 10.4 swap（保命，不是性能方案）

```bash
fallocate -l 2G /swapfile
chmod 600 /swapfile
mkswap /swapfile
swapon /swapfile
echo '/swapfile none swap sw 0 0' >> /etc/fstab
# swappiness=10：尽量只用物理内存，真到紧张时才换页。
# 默认值 60 会让内核过早把热数据换出去，2G 机器上表现为「无缘无故变慢」。
sysctl -w vm.swappiness=10
echo 'vm.swappiness=10' >> /etc/sysctl.conf
free -m
```

### 10.5 连接池与线程数

这两项已在代码侧配好，**不要**在服务器上改：

- `application.yml` → `spring.datasource.hikari`：池子上限 8、拿不到连接 3 秒失败（默认 30 秒会把 Tomcat 线程一起拖满）。
- `application.yml` → `server.tomcat.threads.max`：200 降到 100（2 核跑 200 条线程只是增加上下文切换）。

### 10.6 验证调优效果

```bash
free -m                                  # available 应大于 300M
vmstat 1 5                               # si/so 两列必须是 0，非 0 就是还在换页
ps -o rss=,vsz= -p $(pgrep -f campus-social.jar)   # 看 java 进程实际 RSS，调优后应在 750M 左右
curl -s localhost:8080/actuator/health   # 应为 {"status":"UP"}
```

### 10.7 这台机器的容量上限与扩容顺序

- 日常稳定支撑约 **80–150 人同时在用**，长连接在线约 300–500 人，课间集中冷启动 20–40 人就会排队。
- 容量不够时按这个顺序加，不要乱花钱：
  1. **首页 feed 第一页进 Redis 缓存 60 秒** —— 零成本，砍掉最大热点查询。
  2. **BCrypt cost 12 → 10** —— 登录耗时降到约 1/4（现在 2 核每秒只能处理 5–6 次登录，中午下课会排长队）。
  3. **前端静态资源挂 CDN** —— 3Mbps 出带宽是首屏瓶颈，图片已走 OSS 签名 URL 不占带宽，JS/CSS 再上 CDN 后 ECS 只出 JSON。
  4. **升到 2核4G** —— 真正解决问题的一步，内存翻倍后 swap 消失。
  5. **后端多实例** —— 前提是先把 `WsSessionManager` 改成 Redis pub/sub。它现在是单机内存态，直接开第二个实例会漏推消息。


---

## 11. 探活与日志

- 健康检查：`GET /actuator/health`（只暴露 health，不含 env/beans）。Nginx 不可把它暴露到公网。
- 后端日志：`journalctl -u campus -f` 或 `/opt/app/run.log`（取决于是否重定向）。
- 前端报错：浏览器 Console + Network；`silent:true` 的请求只压 toast，Console 仍会打印（如 503 直传降级），属正常降级信号非失败。

---

## 12. 当前待带上线的本地修复

`frontend/src/composables/useMediaUpload.js` 已修「发帖媒体预览一直转圈」的 Vue 响应式 bug（格子须用 `reactive` 包裹，否则 `cell.state='done'` 不触发重渲染）。该修复**已提交进仓库**（commit 04cfd89），只是尚未部署到 ECS——首次部署时按第 4 节完整构建自然带上，无需单独处理。

---

## 13. 备案前 checklist：备份 / 监控 / CDN 预备（2026-10-25 补）

> 背景：这台 2核2G 单机扛着 MySQL 全部用户数据，当前**没有数据库定期备份、没有监控告警**。
> 单机 + 本机盘，磁盘一坏数据全没；OOM/带宽打满只能等用户来报。三件事都很便宜，备案过审开放注册前务必做完。

### 13.1 数据库每日备份到 OSS（最优先）

利用后端已有的 OSS AK（第 5 节 systemd env 里的同一对，权限已限定为该桶读写——不要为备份新开 root AK）。

```bash
# 一次性：安装 ossutil 并用后端那对 AK 配置（AK 从 systemd unit 的 env 里抄）
# 下载 ossutil：https://help.aliyun.com/document_detail/120075.html
ossutil config -e oss-cn-hangzhou.aliyuncs.com -i <OSS_ACCESS_KEY_ID> -k <OSS_ACCESS_KEY_SECRET>

# 备份脚本 /opt/app/backup.sh
cat > /opt/app/backup.sh <<'EOF'
#!/bin/bash
set -e
STAMP=$(date +%F)
DUMP=/tmp/campus_social_v2-$STAMP.sql.gz
# 单库全量;--single-transaction 对 InnoDB 不锁表;密码避免进 ps,用 defaults-extra-file 更佳
mysqldump --single-transaction --routines campus_social_v2 | gzip > "$DUMP"
# 桶名与第 5 节 OSS_BUCKET 一致;backup/ 前缀单独配生命周期(见下)
ossutil cp "$DUMP" oss://<OSS_BUCKET>/backup/db-$STAMP.sql.gz
rm -f "$DUMP"
echo "backup done: db-$STAMP.sql.gz"
EOF
chmod 700 /opt/app/backup.sh

# MySQL 密码放独立文件(600 权限),不硬编码在脚本里:
# /root/.my.cnf  →  [mysqldump] user=campus password=...

# 每日 04:30 跑(避开凌晨 3 点的 ReconcileJob 全量对账,别抢 CPU/IO)
crontab -e
# 30 4 * * * /opt/app/backup.sh >> /var/log/campus-backup.log 2>&1
```

**OSS 侧一次性设置**：控制台给桶的 `backup/` 前缀配生命周期规则「30 天后删除」——否则备份会无限堆积把桶撑爆（30 天 × 每天 1 份，单份几 MB～几十 MB，成本可忽略）。

**每月做一次恢复演练**：随便挑一份下载、`gunzip` 后导进本地库确认能还原。「有备份」和「备份能恢复」是两件事，前者廉价后者才是保险。

### 13.2 阿里云云监控告警（免费，控制台点一遍）

云监控控制台 → 主机监控（装云监控插件，免费）→ 创建告警规则，阈值与理由：

| 指标 | 阈值 | 为什么 |
|---|---|---|
| CPU 使用率 | > 80% 持续 5 分钟 | 2 核被占满即请求排队 |
| 内存使用率 | > 85% 持续 5 分钟 | 2G 无余量，这是 OOM 前兆（10.6 的调优就是为它兜底） |
| 磁盘使用率 | > 80% | 数据库盘满 = 整站写挂 |
| 公网流出带宽 | > 2.5Mbps 持续 5 分钟 | 3Mbps 是首屏瓶颈，提前知道传播高峰来了 |
| 进程/宕机 | ping 不可达 | Nginx/后端挂了立刻知道，而不是等用户 |

通知方式：绑钉钉机器人（免费且即时）；短信也开一条给宕机告警。

### 13.3 CDN 预备（备案过审后做，现在只需知道）

第 10.7 节已把「前端静态资源挂 CDN」列为扩容第 3 步。具体动作：前端 `dist/` 构建后不再只放 ECS Nginx，同时上传 OSS 的 `static/` 前缀 → 开 CDN 加速域名回源该桶 → `yousheng8.cn` 的静态路径 CNAME 到 CDN 域名。生效后 ECS 只出 API JSON，3Mbps 瓶颈解除（成本：几百 DAU 每月几块钱）。

**备案前不要做**——CDN 加速域名同样要求已备案。这里只留位置，过审后按 10.7 的顺序接入。
