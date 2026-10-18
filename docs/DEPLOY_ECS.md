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
ExecStart=/usr/bin/java -Xmx512m -jar /opt/app/campus-social.jar
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

- JVM 已限堆 `-Xmx512m`（见 systemd ExecStart）。若还 OOM，降到 `-Xmx384m`，但 MyBatis/连接池别开太大。
- MySQL：`/etc/mysql/mysql.conf.d/mysqld.cnf` 加
  `innodb_buffer_pool_size = 256M`，避免吃光内存触发 swap。
- Nginx 默认够用；`worker_processes auto; worker_connections 1024;` 即可。
- Redis 默认最大内存不限，建议 `maxmemory 128mb` + `maxmemory-policy allkeys-lru`，防止缓存撑爆。

---

## 11. 探活与日志

- 健康检查：`GET /actuator/health`（只暴露 health，不含 env/beans）。Nginx 不可把它暴露到公网。
- 后端日志：`journalctl -u campus -f` 或 `/opt/app/run.log`（取决于是否重定向）。
- 前端报错：浏览器 Console + Network；`silent:true` 的请求只压 toast，Console 仍会打印（如 503 直传降级），属正常降级信号非失败。

---

## 12. 当前待带上线的本地修复

`frontend/src/composables/useMediaUpload.js` 已修「发帖媒体预览一直转圈」的 Vue 响应式 bug（格子须用 `reactive` 包裹，否则 `cell.state='done'` 不触发重渲染）。该修复目前只在本地文件、尚未部署——**首次部署或下次迭代请务必把它一起 build 上去**，否则线上用户也会遇到「图其实传成功了、但预览一直转圈」。
