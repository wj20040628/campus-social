# 友声 · 校园生活社区 —— ECS 从零部署清单

适用对象：阿里云 ECS 华东1(杭州)G，`ecs.e-c1m1.large`（2核2G，Ubuntu 22.04，3Mbps）。
公网 `120.26.4.66`，私网 `192.168.25.254`。域名 `yousheng8.cn`（ICP 备案已通过，2026-10-28 起以 `https://yousheng8.cn` 全站 HTTPS 提供服务）。

本文档只讲「怎么把项目跑在服务器上 + 之后怎么迭代」，不重复讲代码逻辑。

---

## 0. 两条铁律

1. **服务器只跑产物，不写代码。** 所有源码改动都在你这台 Windows 开发机完成、验证、提交，再打包发上去。绝不在 ECS 上 vim 改 Java。
2. **配置全走环境变量，不写死在代码里。** 改功能 = 改 `src`；改运行环境（换桶/换密钥/开直传）= 改 systemd 的 `Environment=`。两条线分开。

---

## 1. 备案前 vs 备案后（当前为备案后形态）

ICP 备案卡的是「域名 DNS 解析到这台 ECS + 开放 80/443」。备案已于 2026-10-28 前后过审，下表右列为**线上现状**（2026-10-29 由外部探测核对）。

| 项目 | 备案前（历史记录） | 备案过审后（现状） |
|------|----------------------|------------|
| 访问方式 | 公网 IP + 非标端口 `http://120.26.4.66:8088` | `https://yousheng8.cn`，HTTP/1.1 请求头实测协商到 h2（HTTP/2）；`http://` 返回 301 跳转 HTTPS |
| 前端入口 | Nginx 监听 8088 | Nginx 监听 80/443，443 挂 TLS 证书 |
| TLS 证书 | 无 | Let's Encrypt 签发，`CN=yousheng8.cn`，有效期 2026-10-28 → 2027-01-26（90 天，**须确认自动续期**，见 6.1 节末） |
| OSS 直传 | 关（`OSS_DIRECT_UPLOAD_ENABLED=false`） | 取值需在服务器上核对（见 6.1 节末命令）；回调域名 `https://yousheng8.cn/upload/oss-callback` 已可达 |
| 邮件发信 | 已通（走 ECS RAM 角色，与备案无关） | 不变 |
| 图片读写 | 后端 `POST /upload` 服务端兜底写 OSS，读走 presign 签名 URL | 同左；开直传后前端直传更快 |

> 直传依赖 OSS 回调到「已备案」域名 `yousheng8.cn`，备案期间该回调不通，直传会被 OSS 判失败；服务端兜底路径不依赖回调域名，所以备案期间功能仍完整。
> 备案过审后只需改 Nginx 端口 + 证书 + 把直传开关拨 true，无需改代码，前端 dist 也不用重打。

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

## 3. 目录规划（ECS 上，以线上为准）

```
/opt/campus/                  应用根目录
  campus-social.jar           后端产物（当前版本）
  backup/                     历史 jar + 数据库 dump（campus_social_v2_*.sql.gz，保留 14 天）
  web/                        前端 dist 内容（Nginx root）
  web.bak_<时间戳>/           上一份前端（回滚用）
  uploads/                    本地历史图片（读链路仍在）
  incoming/                   上传暂存，落位后清空
  bin/                        运维脚本（logship.sh）
  backup.sh                   数据库每日备份（campus-backup.timer 调用，见 13.1）
  app.log / error.log         后端日志（systemd 重定向输出）
/etc/systemd/system/campus.service   后端服务单元
/etc/nginx/sites-enabled/default     Nginx 站点配置（三个 server 块）
```

> **路径以这里为准**：2026-10-26 起线上实际用 `/opt/campus`，不是本手册早期写的 `/opt/app`；
> Nginx 站点配置也不在 `conf.d/campus.conf`，而在 Ubuntu 默认的 `sites-enabled/default`
> （`conf.d/` 是空的）。改 Nginx 前先备份该文件。

新机才需要建目录：

```bash
mkdir -p /opt/campus/{backup,web,incoming,bin}
```

---

## 4. 本机构建（在你 Windows 开发机，不在 ECS）

后端打 jar（**生产 jar 里不能带 `application-local.yml`**，那是本地明文密钥；打包前临时移走、打完放回）：

```bash
cd F:\校园社交\backend
mvn clean package -DskipTests
# 产出 backend/target/campus-social.jar
```

前端打 dist（前端连的是相对路径 /api，由 Nginx 反代，build 时无需改 baseURL；本机 `npm run build` 的子 shell 找不到 node，用脚本直跑）：

```bash
cd F:\校园社交\frontend
node scripts/build.mjs
# 产出 frontend/dist/  （index.html + assets/）
```

打包上传物（zip 内路径必须是正斜杠，PowerShell `Compress-Archive` 会写反斜杠、Linux 解压即坏）：

```bash
cd F:\校园社交\frontend\dist
C:\Windows\System32\tar.exe -a -c -f F:\校园社交\dist_YYYYMMDD_vN.zip \
  index.html assets apple-touch-icon.png favicon.png favicon.svg
# 注意排除 dist/dist.zip（历史遗留旧包）
```

只传产物，且**只传进暂存目录**（真正生效的落位见第 8 节，暂存目录用完即删）：

```bash
scp backend/target/campus-social.jar root@120.26.4.66:/opt/campus/incoming/
scp dist_YYYYMMDD_vN.zip root@120.26.4.66:/opt/campus/incoming/
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
WorkingDirectory=/opt/campus
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
# -XX:+ExitOnOutOfMemoryError  堆真 OOM 时直接退出，让 systemd 5 秒后拉起。
#   不加的话 JVM 会半死不活：请求持续 500，但进程还在、探活可能仍返回 UP，
#   外部监控看不出异常，只能等用户报障。
# 线程数上限不在这里配，已写进 application.yml（server.tomcat.threads.max=100），
# 改那一处即可，不必重启机器。
ExecStart=/usr/bin/java -Xms256m -Xmx384m -XX:MaxMetaspaceSize=192m -XX:+ExitOnOutOfMemoryError -jar /opt/campus/campus-social.jar
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

> **线上现状（2026-10-29）**：站点配置在 `/etc/nginx/sites-enabled/default`（Ubuntu 系统 Nginx；
> `conf.d/` 是空目录），前端根目录 `/opt/campus/web`，共三个 server 块：
> ① IP 直连 80（应急）② 域名 80（301 → https）③ 443 正式站点（Let's Encrypt 证书，
> 含 `/api/`、`/uploads/`、`/ws/`、`= /index.html`、`/` 五个 location）。
> **不要整体套用下面的备案前模板**，它只作存档；线上改动一律按 6.1 的增量补丁做。

### 6.1 本次上线增量补丁（1.11.0，2026-10-29）

先确认现有配置长什么样（把 200m 的位置找出来）：

```bash
cp /etc/nginx/sites-enabled/default /root/nginx-default.bak_$(date +%F_%H%M)
grep -n "client_max_body_size\|location" /etc/nginx/sites-enabled/default
```

三处改动，贴进 **443 正式站点**的 `server { }`（IP 直连块建议同步，应急入口也受同样的限制）：

```nginx
    # 1) 上传上限收口:与后端 max-request-size=30MB 对齐,覆盖 25MB 视频 + multipart 开销。
    #    先看 grep 结果:200m 在哪一层(server 或 /api/ location),就地把那个值改成 32m;
    #    若一处都没有,才把下面这行加在 server { } 里。同一层留两条同指令 nginx -t 会报错。
    client_max_body_size 32m;

    # 2) 公网探活:外部监控打 https://yousheng8.cn/healthz。
    #    只反代 /actuator/health(show-details=never,响应只有 {"status":"UP"}),
    #    后端或数据库挂掉时返回 502/503,监控据此告警。
    location = /healthz {
        proxy_pass http://127.0.0.1:8080/actuator/health;
        proxy_set_header Host $host;
        access_log off;
        add_header Cache-Control "no-store";
    }

    # 3) 慢连接保护:默认 60s,半开连接会长期占住 worker。
    #    只在「连续无进展」时计时,正常弱网上传 25MB 视频不受影响。
    client_header_timeout 15s;
    client_body_timeout 30s;
    send_timeout 60s;
```

应用与自测：

```bash
nginx -t && systemctl reload nginx
curl -s https://yousheng8.cn/healthz                 # 期望 {"status":"UP"}
curl -sI https://yousheng8.cn/healthz | grep -i content-type   # 期望 application/json
```

> 若 `/healthz` 拿回 `text/html`（前端入口页），说明 location 没匹配上（落进了 SPA 回退），
> **先修配置再配监控**——否则监控会永远显示「正常」。

以下为备案前最小模板（**存档，勿整体套用**）：

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
    root /opt/campus/web;
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

备案过审后（**已执行，2026-10-28**）：把 `listen 8088` 改为 `listen 80; listen 443 ssl http2;`，`server_name` 换成 `yousheng8.cn www.yousheng8.cn`，加 `ssl_certificate` / `ssl_certificate_key`，并让 80 端口只做事后跳转：

```nginx
server {
    listen 80;
    server_name yousheng8.cn www.yousheng8.cn;
    location /.well-known/acme-challenge/ { root /var/www/certbot; }   # 证书续期校验用
    location / { return 301 https://$host$request_uri; }
}
```

证书实际用的是 Let's Encrypt（不是原计划的阿里云免费 DV 证书），外部探测结果：`CN=yousheng8.cn`、签发者 `Let's Encrypt YR2`、有效期 2026-10-28 → 2027-01-26、ALPN 协商到 `h2`。这类证书只有 90 天，**必须确认续期是自动的**，否则 2027-01-26 之后浏览器直接报证书失效：

```bash
systemctl list-timers | grep -i certbot          # 有 certbot-renew.timer 且在跑 = 自动续期
ls /etc/letsencrypt/renewal/                     # 有 yousheng8.cn.conf = certbot 在管这个域名
certbot renew --dry-run                          # 演练续期，不改线上证书，可放心跑
```

防火墙与 OSS 直传也要跟着改（改完 `systemctl daemon-reload && systemctl restart campus`，前端 dist 无需重打）：

```bash
ufw allow 80 && ufw allow 443 && ufw delete allow 8088   # 后端 8080 始终不对公网
grep Environment /etc/systemd/system/campus.service | grep OSS_DIRECT_UPLOAD   # 查直传开关现值
```

> 已知漂移，改配置前先核线上真实文件（`nginx -T > /tmp/nginx-running.conf` 看生效配置）：
> ① 仓库 `frontend/nginx.conf` 是 Docker 用的模板（含 `gzip on`），线上站点配置在服务器上单独维护，实测线上**未开传输压缩**——`https://yousheng8.cn/assets/index-*.js` 带 `Accept-Encoding: gzip` 请求仍无 `Content-Encoding`，333810 字节原样下发，3Mbps 带宽下拖慢首屏；
> ② 备案期文档写的「阿里云免费 DV 证书」已被 Let's Encrypt 取代，续期机制与阿里云证书完全不同（前者靠 certbot 定时器，后者到期手动重签）。

---

## 7. 数据库变更（加功能改表时）

加字段/加表，走 SQL 脚本，不当场手敲：

1. 本机 MySQL 改好，同时把变更写成 `sql/migration_YYYYMMDD.sql`（建表/加列/加索引）。
2. 部署时把脚本传 ECS 执行：
   ```bash
   scp sql/migration_xxx.sql root@120.26.4.66:/opt/campus/incoming/
   ssh root@120.26.4.66 "mysql -ucampus -p campus_social_v2 < /opt/campus/incoming/migration_xxx.sql"
   ```
3. 后端代码用新结构。

仓库里已有的增量脚本（按序执行）：`sql/migration_f11_joined_circle_count.sql`、`sql/migration_f12_school_major.sql`、`sql/migration_f12_seed_school_major.sql`、`docs/sql/migration_v2.sql`。首次部署若 `schema.sql` 已含这些，可跳过；不确定就先 `show tables` 比对。

**2026-10-27 文件库（v1.4.0）额外两步：**

1. 执行 `sql/migration_n1_file_library.sql`（新建 `file_asset` / `file_favorite`，并更新 `like.target_type` 注释）。文件库的配额、审核队列、点赞收藏全依赖这两张表，漏执行时上传接口会直接 500。
2. 执行 `sql/migration_n2_post_share_count.sql`（`post` 新增 `share_count`）。**这次必须执行**：实体已带该字段，MyBatis-Plus 的查询会显式列出 `share_count`，漏列会让帖子列表/详情直接 SQL 报错，不只是分享接口失败。
2. OSS 权限补 `files/` 前缀。文件库对象写在 `files/{userId}/...`，**不是图片用的 `uploads/`**：
   - 生产是 `OSS_CREDENTIAL_TYPE=ecs_ram_role` + `OSS_ECS_ROLE_NAME=mail`（实例 RAM 角色，无明文 AK）。在该角色的权限策略里追加：
     ```json
     {
       "Effect": "Allow",
       "Action": ["oss:PutObject", "oss:GetObject", "oss:DeleteObject"],
       "Resource": ["acs:oss:*:*:yousheng-uploads/files/*"]
     }
     ```
   - 少了 Put 上传 403；少了 Get 管理员下载/读校验失败；少了 Delete 用户删除与 7 天清理任务只清库不删对象。
   - **别被 RAM「摘要(Beta)」的黄色「无效授权 / 无-未定义有效资源」唬住**（2026-10-27 实测）：OSS 在 RAM 元数据里不登记「资源类型」，所以分析器解析不了任何带 ARN 的 OSS 语句，见 RAM 的 OSS 产品元数据「此产品不支持对具体资源授权」「不支持在 Resource 中指定资源 ARN」。OSS 服务端鉴权仍按 Resource 前缀判定，官方示例通篇是 `acs:oss:*:*:bucket/dir/*`。
   - 真正决定生效的是两件事：① 策略**创建后必须授权给角色 `mail`**（策略详情页的「授权管理」里要能看到 `mail`，只有「删除权限策略」按钮说明还没绑）；② Resource 写法与线上已验证可用的 `OssUploadOnly` 保持一致（抄它的源代码，只把 `uploads/` 换成 `files/`），并把 `*:*` 收紧成 `*:1930391355152122`（账号 UID）。
   - 判定以功能为准：部署后第一次上传就是测试，失败时 `journalctl -u campus --since "5 min ago" | grep -iE "403|AccessDenied"`。
   - 固定 AK 模式（`OSS_STS_ACCESS_KEY_ID`）的 STS 临时令牌当前仍锁 `uploads/{userId}/*`，文件库上传会 403；生产不要切回该模式。
   - 本次没有新增必填环境变量：`file.*` 全部有默认值（单文件 3MB、每日 5 个、待审 5 个、下载限流），需要调再注入 `FILE_*`。
   - 在线预览（PDF / TXT / MD）由后端取字节后 inline 返回：OSS 走**内网**读取，再经 ECS 出到浏览器，消耗 ECS 出带宽而不是 OSS 外网流量；Word/PPT/Excel 仍提示下载。前端 PDF.js 首次预览会多加载约 0.5MB（gzip，含 worker），之后浏览器缓存。

**2026-10-29 举报快照（v1.11.0）一步：**

执行 `sql/migration_n3_report_snapshot.sql`（`report` 新增 `target_snapshot`）。实体已映射该列，漏执行时提交举报 / 管理端举报列表直接 SQL 报错。无新增环境变量。

---

## 8. 迭代部署（改功能/加功能后）

**落位约定（2026-10-26 定）**：上传的包一律只放暂存目录 `/opt/campus/incoming/`，用完即删。只有三个位置真正生效：

> **宝塔怎么进 `/opt/campus/incoming`**：文件管理器顶部有路径栏（面包屑），把里面的 `/www/wwwroot/xxx` 清掉，
> 直接输入 `/opt/campus/incoming` 回车。宝塔默认停在网站根目录，`/opt` 不在左侧树里，必须手输路径。
> 传完点工具栏的 **终端** 按钮，终端会直接停在当前目录，不用再 cd。

| 上传物 | 生效位置 | 生效动作 |
| --- | --- | --- |
| `campus-social.jar` | `/opt/campus/campus-social.jar` | 覆盖后必须 `systemctl restart campus` |
| `dist_*.zip` 解出的内容 | `/opt/campus/web/`（整目录替换） | Nginx 立即生效，不重启 |
| `migration_*.sql` | 导入 MySQL，不留目录 | 导入即生效 |

`uploads/`、`.env`、`backup/` 不塞安装包。闭环：

```bash
# 1. 本机改代码，本地 8080 + 5173 联调 + 浏览器实测通过（见 frontend/scripts 与 .e2e-env）

# 2. 本机重新构建（见第 4 节），产物传 ECS 暂存目录
scp backend/target/campus-social.jar root@120.26.4.66:/opt/campus/incoming/
scp dist_YYYYMMDD_vN.zip root@120.26.4.66:/opt/campus/incoming/

# 3. 在 ECS 上：先备份，再落位（时间戳后缀，回滚靠它）
TS=$(date +%Y%m%d_%H%M%S)
cp /opt/campus/campus-social.jar /opt/campus/backup/campus-social.jar.$TS
mv /opt/campus/web /opt/campus/web.bak_$TS
mkdir -p /opt/campus/web && cd /opt/campus/incoming
unzip -q dist_YYYYMMDD_vN.zip -d /opt/campus/web

# 必须先停服务再覆盖 jar：JVM 运行中替换 jar，旧进程关闭时
# 会因 classloader 读不到 logback 类而在 error.log 刷 NoClassDefFoundError（不影响新进程，但会污染日志）
systemctl stop campus
cp campus-social.jar /opt/campus/campus-social.jar

# 4. 若这次改了表，先导入 migration 再启动（顺序不能反：新代码依赖新列）
mysql -ucampus -p campus_social_v2 < migration_n1_file_library.sql
mysql -ucampus -p campus_social_v2 < migration_n2_post_share_count.sql

systemctl start campus

# 5. 探活 + 清暂存（日志由 systemd 重定向到 app.log/error.log，不在 journalctl）
for i in $(seq 1 30); do sleep 3; curl -fsS --max-time 3 localhost:8080/actuator/health && break; done
tail -n 80 /opt/campus/app.log | grep -E "Started CampusSocialApplication|ERROR"
tail -n 20 /opt/campus/error.log
rm -f /opt/campus/incoming/*
```

验证：用手机/换号点一遍改动涉及的功能（尤其发帖、聊天、头像这几个之前踩过坑的路径）。
2C2G 启动要 20-40 秒，健康检查至少等 30-40 秒再看结果，8 秒会误判启动失败。

---

## 9. 回滚

```bash
TS=<部署时记录的时间戳>
systemctl stop campus
cp /opt/campus/backup/campus-social.jar.$TS /opt/campus/campus-social.jar
systemctl start campus

rm -rf /opt/campus/web && cp -r /opt/campus/web.bak_$TS /opt/campus/web
```

前端覆盖即生效，不需要 `nginx -s reload`（`location = /index.html` 已配 `Cache-Control: no-cache`；微信里打不开新版就加 `?v=数字` 强刷）。

数据在 MySQL 里，与 jar/web 分离，回滚不影响用户数据。若回滚涉及「表结构也回退」，需手动写反向 migration（谨慎，通常不做，改用新 migration 修补）。

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

调优前的状态不是「慢」，是「不稳」：剩余内存不足 120M，MySQL 拿不到文件缓存，一旦并发上来就触发 swap，延迟从几十毫秒跳到几百毫秒。先按 10.0 核对现状，缺哪项补哪项，最后按 10.6 验收。

### 10.0 先核对：线上到底生效没有（2026-10-29 补）

参数写在文档里 ≠ 已经生效。整段复制到 ECS 终端跑一遍，对照后面的表：

```bash
echo "== swap =="; swapon --show; free -m; echo "swappiness=$(cat /proc/sys/vm/swappiness)"
echo "== JVM =="; systemctl cat campus | grep -E '^ExecStart'
ps -o rss= -p "$(pgrep -f campus-social.jar | head -1)" | awk '{printf "java RSS ≈ %d MB\n", $1/1024}'
echo "== MySQL =="; mysql -ucampus -p -e "SHOW VARIABLES WHERE Variable_name IN ('innodb_buffer_pool_size','innodb_buffer_pool_instances','performance_schema','max_connections');"
echo "== Redis =="; redis-cli CONFIG GET maxmemory maxmemory-policy
echo "== 余量 =="; free -m | awk 'NR==2{print "available:", $7 " MB"}'
echo "== 健康 =="; curl -s localhost:8080/actuator/health; echo
```

| 输出项 | 已生效的样子 | 不符合就去做 |
|---|---|---|
| `swapon --show` | 有 swap 行、≥1G（线上现为 `/www/swap` 1G） | 10.4 |
| `swappiness` | `10` | 10.4 |
| `ExecStart` | 含 `-Xms256m -Xmx384m -XX:MaxMetaspaceSize=192m` | 10.1 |
| java RSS | 700–820 MB（`ps` 给的是 KB，÷1024） | >900M=堆参数没生效；刚重启时偏低属正常 |
| `innodb_buffer_pool_size` | `134217728`（128M） | 10.2 |
| `innodb_buffer_pool_instances` | `1` | 10.2 |
| `performance_schema` | `OFF` | 10.2 |
| `maxmemory` | `134217728`（128mb） | 10.3 |
| `maxmemory-policy` | `noeviction` | 10.3 |
| `available` | > 300 MB | 先补上面几项再看 |
| health | `{"status":"UP"}` | `journalctl -u campus --since "10 min ago"` |

> 补 10.1–10.4 会重启 JVM / MySQL / Redis，**放在同一个低峰窗口一次做完**（例如早 7 点前）。
> 中间会有几十秒接口报错，提前在群里说一声；不要只重启 Java 就当全部生效。

### 10.1 JVM 堆

目标参数（与第 5 节 systemd 的 `ExecStart` 一致）：
`-Xms256m -Xmx384m -XX:MaxMetaspaceSize=192m -XX:+ExitOnOutOfMemoryError`。

线上若还没生效，**不要直接改原 unit 文件**（以后整份替换会丢），用 drop-in 覆盖。2026-10-29 已按下面的 drop-in 在线上生效：

```bash
mkdir -p /etc/systemd/system/campus.service.d
cat > /etc/systemd/system/campus.service.d/memory.conf <<'EOF'
[Service]
# 第一行空的 ExecStart= 是 systemd 的「清空原命令」语法，不能删
ExecStart=
ExecStart=/usr/bin/java -Xms256m -Xmx384m -XX:MaxMetaspaceSize=192m -XX:+ExitOnOutOfMemoryError -jar /opt/campus/campus-social.jar
EOF
systemctl daemon-reload
systemctl restart campus
systemctl cat campus | grep ExecStart   # 确认只剩新的一行
```

> java 绝对路径与 jar 路径必须和线上原命令一致，先 `systemctl cat campus` 看一眼再写。

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

# 连接数上限（可选，线上未改，保持默认 151）。
# max_connections 是「上限」不是预分配：MySQL 只对真实连接分配缓冲，
# 应用 Hikari 池上限只有 8，平时真实连接约 8–12 条，151 本身不占内存。
# 压到 50 省不出可测量的内存，反而缩小备份/导库/排障的余量；要严格防连接泄漏再设。
# max_connections = 50
```

```bash
systemctl restart mysql
mysql -ucampus -p -e "SHOW VARIABLES LIKE 'innodb_buffer_pool_size'; SHOW VARIABLES LIKE 'performance_schema';"
```

### 10.3 Redis

Redis 里存的是**验证码、封禁计划、未读数**，不是可随时丢失的页面缓存。因此不设淘汰策略，改为「限额 + 满了就报错」：

```bash
# 线上 Redis 绑 127.0.0.1，未设密码（设了才需要 -a '<密码>'）
redis-cli CONFIG SET maxmemory 128mb
redis-cli CONFIG SET maxmemory-policy noeviction
redis-cli CONFIG GET maxmemory maxmemory-policy   # 回读确认
# 同时写进配置文件，否则重启失效
```

```conf
# 追加到 /etc/redis/redis.conf
maxmemory 128mb
maxmemory-policy noeviction
```

> 为什么不用 `allkeys-lru`：LRU 会按「最久未用」淘汰任意 key，验证码被淘汰只是让用户重发一次，但**封禁计划被淘汰就意味着这个用户永远不会自动解封**——这是数据错误，不是缓存穿透。`noeviction` 在内存写满时返回错误，问题会暴露在日志里而不是悄悄丢数据。实际数据量远低于 128M，这个上限只是保险丝。

### 10.4 swap（保命，不是性能方案）

> 已有 1G swap 的机器可以先保留（线上是 `/www/swap` 1G，2026-10-29 实测 si/so=0）。
> 1G 对 1.7G 物理内存已在合理区间；swap 是兜底，不是容量，真吃满时体验会先崩，
> 该加的是物理内存。要补就**新增**一个 swapfile，不要 resize 正在用的那个。
> 新机按下文建 2G：

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

把 10.0 那段整段再跑一遍，逐项对照表格；另外看是否还在换页：

```bash
vmstat 1 5    # si/so 两列必须是 0，非 0 就是物理内存仍不够，还在 swap
```

稳住之后再过 24 小时看一次 `free -m` 的 available 与 `vmstat`，两项都正常才算调优完成。

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

- 本机探活：`curl -s localhost:8080/actuator/health`（只暴露 health，不含 env/beans，明细也不返回）。
- 公网探活：`https://yousheng8.cn/healthz` —— 6.1 节的 Nginx 反代，响应只有 `{"status":"UP"}`，专给外部监控用。
  除 health 外的 actuator 端点一律不对外。
- **不能拿首页探活**：`/` 和任何前端路径都会被 SPA 回退成 200 index.html，后端、MySQL 全挂了首页照样 200。
- 后端日志：`journalctl -u campus -f`，或 `/opt/campus/app.log`、`/opt/campus/error.log`。
- 前端报错：浏览器 Console + Network；`silent:true` 的请求只压 toast，Console 仍会打印（如 503 直传降级），属正常降级信号非失败。

---

## 12. 当前线上版本与本地状态

生产在 **1.13.2**（2026-10-30 部署，前端入口 `index-kJtT8_w_.js`，jar md5 `985a19b9…`，部署包 `ys_deploy_20261030_v1132.zip`，md5 `e5c7b067…`）：

- **评论列表跟随帖子状态**：下架/退回审核的帖子，评论不再能按 id 读取（作者本人放行，与详情同口径）。
- **游客动作提示**：活动报名、举报先弹「登录后才能…」，不再点了没反应；游客也不再看到「不看这个作者」入口。
- **IP 取值防伪造**：优先 Nginx 覆写的 `X-Real-IP`，否则取 XFF 最后一段；用于登录失败锁定、游客下载限额与审计 IP。
- 公网已验证（2026-10-30）：`/healthz` UP；首页入口 `index-kJtT8_w_.js`；游客 feed 200、`/user/me` 401、`POST /auth/campus-email/send` 404；不存在帖子的详情与评论均返回 1009（评论接口新增的帖子校验已生效，据此确认后端也是 1.13.2）。
- 无数据库结构变更。备份由部署包内 `deploy.sh` 生成：`backup/campus-social.jar.<时间戳>` 与 `web.bak_<时间戳>`。
- **打包注意**：jar 必须 `mvn clean package`。只把 `application-local.yml` 移出 resources 再 `package`，`target/classes` 里的旧配置仍会被打进 jar（2026-10-30 实际踩到）。

**1.13.2 基线复核（2026-10-30，本地）**

- 源码：`22c0608` + 版本元数据提交，标签 `v1.13.2`；`frontend/package.json` 与 `package-lock.json` 均为 1.13.2。
- 本地库：已备份到 `.e2e-env/backup/campus_social_v2_20261030_freeze.sql.gz`，恢复演练 41/41 表一致。
- 前端：`npm ci` 成功；11 条门禁 0 失败；构建入口 `index-kJtT8_w_.js` 与线上一致；首屏 JS gzip 174 KB（预算 300 KB）。
- 后端：`mvn clean test` 142/142；`mvn clean package` 成功；jar 743 个条目，与线上 jar 逐文件内容一致，无 `application-local.yml`。
- 部署包：197 个条目、无反斜杠路径；jar MD5 与包 MD5 均与冻结记录一致；成功与健康检查失败两种回滚模拟均通过（失败时旧 jar 与 web 恢复）。
- 已知脚本瑕疵：回滚时 `web.bak_*` 会被移回 `web/`，脚本仍打印「备份仍保留 web.bak_*」，该提示不准确；不影响恢复结果，留待下一版修正。
- 应用版本唯一来源为 `frontend/package.json` = 1.13.2；后端 Maven 坐标仍为 1.0.0，保持与已上线 jar 相同的构建配方。
- 未在本次执行：生产库备份、OSS 异地文件检查与生命周期、云监控告警、线上 Nginx 对齐，均需服务器或阿里云控制台权限。

上一版 1.13.1（2026-10-30，前端入口 `index-biSlsd1H.js`，jar md5 `ebc0f98e…`）：

- **删除校园邮箱验证码整条链路**：学生没有学校发放的校园邮箱，按域名白名单放行只会误伤；学号核验与管理员人工核对保留。
- **游客导航修复**：底栏、侧栏、抽屉不再显示消息/我的/通知/公告/文件等登录入口；误入登录态页面先给「先逛逛」退路。
- 公网已验证：`/healthz` UP，游客 feed/热门/圈子/集市/用户卡 200，`/files`、`/chat/conversations`、`/announcement/list` 401，`POST /api/verify/campus-email` 后端 404（旧端点确已移除）。

上一版 1.13.0（2026-10-30 16:36 部署，前端入口 `index-zDQwAFBK.js`，jar md5 `7296bb5c…`）：

- **认证退出权限判定**：注册即可发帖、评论、点赞收藏、加入圈子、回复私信与群聊，不再要求先完成校内认证。
- **行为信任门槛**：陌生人搭话、建圈、建群/邀请、圈子活动报名、集市发布改按账号注册时长 + 信誉分（≥60）判定；阈值在 `application.yml` 的 `campus.access-*`。
- **校园信息降为可选资料**：学号只校验 13–14 位格式与唯一性；与院系/年级/专业一致时自动登记，不一致转人工核对，不再直接拒绝。
- **公开页去徽标**：主页、公告、文件、推荐同学不再显示认证状态或学校名；前端删除认证弹层与「去认证」按钮。
- **会话提示**：发不出搭话时直接显示真实原因（账号太新 / 信誉分不足），不再先显示「今天还剩 1 条」再被拒。
- 无数据库结构变更，本次未执行迁移 SQL。
- 备份：`backup/campus-social.jar.20261030_163621`、`web.bak_20261030_163621`。

上一版 1.12.1（2026-10-30 11:46，前端入口 `index-B_-uSOMu.js`，jar md5 `88334596…`）：
游客可读范围扩大、游客登录、视频分享卡封面。

1.11.2（2026-10-29 13:35，前端入口 `index-Uno-rceA.js`）只换前端，未重启后端：

- **视频封面**：上传时用 canvas 从本地文件截帧（0.5s / 25% / 50% 取最亮，跳过黑场），作为图片上传后写进 `media` JSON 的 `poster`；
  首页/详情/预览统一走 `PostVideo.vue`，旧帖无 poster 时 `#t=1` 兜底。**1.11.2 之前上传的视频没有 poster，需重新上传一次才有封面。**
- 备份：`web.bak_20261029_133502`。

1.11.1（13:24，jar md5 `46bf7b8f…`）已包含：

- **上传进度**：图片/视频显示百分比、进度条、约剩时间；上传完成前不能点发布；上传超时 120s → 5 分钟。
- **上传提速**：服务端转存 OSS 由公网端点改为**内网端点**（`OssService.putObject`，内网不可达自动回退公网）。
  此前 18.85MB 视频光服务端转存就要约 50 秒——ECS 出公网只有 3Mbps，这是「传一分钟」的主因。
- 备份后缀 `20261029_132244`（`backup/campus-social.jar.20261029_132244`、`web.bak_20261029_132244`）。

> 1.11.0（12:48）已包含：举报快照 `report.target_snapshot`、上传上限 25MB/30MB/32m、公网 `/healthz`、
> JVM 384m、Redis 128M、swappiness=10。相关备份见 20261029_124726 / nginx-default.bak_20261029_124637。

线上为 1.13.2。尚未做的两项：Swap 仍为 `/www/swap` 1G、云监控站点任务需在控制台手工建（13.2）。

---

## 13. 备案前 checklist：备份 / 监控 / CDN 预备（2026-10-25 补）

> 背景：这台 2核2G 单机扛着 MySQL 全部用户数据。
> 单机 + 本机盘，磁盘一坏数据全没；OOM/带宽打满只能等用户来报。三件事都很便宜，备案过审开放注册前务必做完。
>
> 现状（2026-10-29）：**数据库每日备份已上线并做过恢复演练**；外部监控与 CDN 仍未配置。

### 13.1 数据库每日备份到 OSS（已上线）

`/opt/campus/backup.sh` + systemd timer `campus-backup.timer`，每天 **04:30**（避开 03:00 ReconcileJob 与 03:30 logship）跑：

- 本地 `/opt/campus/backup/campus_social_v2_<时间戳>.sql.gz`，保留 14 天；
- 同步上传 `oss://yousheng-uploads/logs/db/<时间戳>.sql.gz` 异地留存；
- dump 参数：`--single-transaction --quick --no-tablespaces --routines --triggers --events`，密码走 `MYSQL_PWD` 不进 `ps`；
- 三重校验后才算成功：非空 + `gzip -t` + `zgrep 'CREATE TABLE'`；任一步失败退出非 0，systemd 记为 failed。

> **为什么是 `logs/db/` 而不是 `backup/`**：ECS RAM 角色 `mail` 的策略只授权 `logs/*`，`backup/` 前缀 PUT 会 `AccessDenied`（报错文案是误导性的 "bucket acl"）。要独立前缀需在 RAM 控制台给策略加 `acs:oss:*:*:yousheng-uploads/backup/*` 的 Put/Get/Delete，加完再改脚本里的 `OSS_KEY`。

**日常核查**（三条都应为正常）：

```bash
systemctl list-timers campus-backup.timer --no-pager   # NEXT 应为次日 04:30
journalctl -u campus-backup.service -n 20 --no-pager   # 每次一条「备份成功」
ls -lht /opt/campus/backup/campus_social_v2_*.sql.gz   # 最近一份应是今天/昨天
ossutil ls oss://yousheng-uploads/logs/db/ -e oss-cn-hangzhou.aliyuncs.com --region cn-hangzhou --mode EcsRamRole
```

**恢复演练（2026-10-29 已做，41/41 表、user/post/comment/notify/report 行数一致）**：

```bash
DB=campus_restore_check
mysql -uroot -e "DROP DATABASE IF EXISTS $DB; CREATE DATABASE $DB CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;"
gunzip -c /opt/campus/backup/campus_social_v2_<时间戳>.sql.gz | mysql -uroot "$DB"
mysql -uroot -N -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$DB'"   # 应等于生产表数
mysql -uroot -e "DROP DATABASE $DB"
```

**尚未做**：备份失败没有主动告警（只有 `systemctl --failed` 能看见）；OSS 侧没配生命周期，远端会无限累积（单份 20K 上下，一年 ~7MB，可忽略；要清就在控制台给 `logs/db/` 配 30 天删除）。

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

**站点监控（HTTP 探活，2026-10-29 补）**：主机监控只能看出「机器活着」，看不出「后端/数据库挂了」。
在云监控 → 站点监控新建一个 HTTP(S) 任务：

| 项 | 值 |
|---|---|
| 监控地址 | `https://yousheng8.cn/healthz`（先按 6.1 配好并用 curl 自测通过） |
| 请求方法 | GET |
| 监测频率 | 5 分钟 |
| 匹配规则 | 响应内容包含 `"status":"UP"` |
| 告警 | 连续 2 次失败 → 钉钉机器人 + 短信 |

> 只判断 HTTP 200 不够：`/healthz` 若没配好，会落进 SPA 回退返回 200 的 `index.html`，
> 监控就会永远显示「正常」。所以必须做关键词匹配。
>
> 云监控站点监控没有免费额度时，用 UptimeRobot 免费版（50 个监控、5 分钟间隔）同样配
> `https://yousheng8.cn/healthz` + keyword `"status":"UP"`。
>
> 为什么不用首页探活：首页是静态文件，Nginx 活着就 200；Java 或 MySQL 挂了首页照样「正常」。
> `/healthz` 走 Spring 的 health（含数据库检查），后端或库挂了返回 502/503。

### 13.3 CDN 预备（备案过审后做，现在只需知道）

第 10.7 节已把「前端静态资源挂 CDN」列为扩容第 3 步。具体动作：前端 `dist/` 构建后不再只放 ECS Nginx，同时上传 OSS 的 `static/` 前缀 → 开 CDN 加速域名回源该桶 → `yousheng8.cn` 的静态路径 CNAME 到 CDN 域名。生效后 ECS 只出 API JSON，3Mbps 瓶颈解除（成本：几百 DAU 每月几块钱）。

**备案前不要做**——CDN 加速域名同样要求已备案。这里只留位置，过审后按 10.7 的顺序接入。
