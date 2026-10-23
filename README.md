# 友声 · 校园生活社区

> 嘤其鸣矣，求其友声。 ——《诗经·小雅·伐木》

让校园里的每一次发声,都能得到回应。

工程代号:campus-social

技术栈:**Vue3 (Vite) + SpringBoot 3.2 + MySQL 8 + Redis + JWT 双 token + Spring 原生 WebSocket + Element Plus + Pinia**

---

## 功能模块

| 模块 | 说明 | 状态 |
|---|---|---|
| M1 账号 | 邮箱/手机号+密码注册、JWT 双 token、refresh 吊销、学号可选 | ✅ |
| M2 关注 | 关注/取关/好友/黑名单,事务内计数 ±1,游标分页 | ✅ |
| M3 帖子+Feed | 写扩散(校园粉丝<5000)、游标分页、图文视频、敏感词 DFA、点赞评论、热度分 | ✅ |
| M4 私信 | 会话+消息、WS 实时推送、已读/未读计数 | ✅ |
| M5 通知 | 点赞/评论/关注事件、WS 推送、未读计数 | ✅ |
| M6 圈子+活动+搜索 | 圈子成员管理、活动报名(幂等+乐观锁防超卖)、全局搜索 | ✅ |

> 设计依据:[AI提示词通用模板.md](AI提示词通用模板.md) 的"领域规则包 B(社交/社区)"。
> 详细开发计划:[`C:\Users\Administrator\.claude\plans\ai-md-sunny-noodle.md`](../../.claude/plans/ai-md-sunny-noodle.md)

---

## 目录结构

```
校园社交/
├─ backend/        SpringBoot 后端(单模块 com.campus.social)
│  ├─ src/main/java/com/campus/social/
│  │  ├─ controller/  REST 接口(无业务逻辑)
│  │  ├─ service/impl  业务 + @Transactional
│  │  ├─ mapper/       MyBatis-Plus BaseMapper
│  │  ├─ entity/       PO(37 张表)
│  │  ├─ dto/ vo/      入参 / 出参
│  │  ├─ common/       Result + ResultCode + CursorPage
│  │  ├─ exception/    BusinessException + GlobalExceptionHandler
│  │  ├─ config/       Jwt/Redis/MybatisPlus/WebSocket/WebMvc
│  │  ├─ interceptor/  AuthInterceptor(HTTP JWT)
│  │  ├─ websocket/    WsHandler + WsSessionManager + WsPushService + WsHandshakeInterceptor
│  │  ├─ task/         敏感词刷新 + 活动状态机定时任务
│  │  └─ util/          JwtUtil + SensitiveWordFilter(DFA)
│  └─ src/main/resources/application.yml
├─ frontend/       Vue3 前端
│  └─ src/
│     ├─ api/          按域拆分的接口模块
│     ├─ views/        页面(Login/Register/Home/Publish/PostDetail/Profile/Settings/Chat/Notify/Circles/CircleDetail/ActivityDetail/Search)
│     ├─ stores/       Pinia(user 唯一持 token)
│     ├─ components/   PostCard
│     ├─ layouts/      AuthLayout / MainLayout
│     ├─ router/       路由 + 守卫
│     └─ utils/        request.js(401 刷新队列) / ws.js(WS 单例重连)
├─ sql/
│  ├─ schema.sql       37 张表完整基线(建库只跑这一个文件;2026-10-23 合并)
└─ .gitignore
```

---

## 快速开始

### 1. 准备环境

- JDK 17+
- MySQL 8.x(创建 `campus_social_v2` 库,跑 [sql/schema.sql](sql/schema.sql) —— 完整基线,37 张表一次建齐)
- Redis 6+(默认 `127.0.0.1:6379`)
- Node 18+

### 2. 启动后端

```bash
cd backend
# 配置 MySQL/Redis 连接(或用环境变量覆盖)
mvn spring-boot:run
```

关键配置在 `backend/src/main/resources/application.yml`:
- `MYSQL_HOST` / `MYSQL_USER` / `MYSQL_PASSWORD`(默认 127.0.0.1/root,密码走 .env,源码不留默认值)
- `REDIS_HOST` / `REDIS_PORT`
- `JWT_ACCESS_SECRET` / `JWT_REFRESH_SECRET`(至少 64 字节,生产用强随机)
- `UPLOAD_DIR`(默认 ./uploads,相对路径,不写本机绝对路径)

#### 密钥管理约定（重要）

**原则：凭据只进 `.env`，源码一律 `${ENV:}` 引用，默认值留空。**

- 根目录 `.env`（已入 `.gitignore`，绝不入库）是本机真值的唯一存放处；
  模板在 `.env.example`，**只列变量名 + 占位说明，不写真值**。
  首次使用：`cp .env.example .env` 再填真值。
- `application-local.yml`（同样 gitignored，不进仓库）里所有凭据段都是
  `${MYSQL_PASSWORD:}` / `${JWT_ACCESS_SECRET:}` 这种「带空默认」的引用，
  **绝不**把密钥写进 `local` 文件的默认值里。原因：把默认值填成真实密钥
  等于又把它写回源码，一旦误提交 `.gitignore` 形同虚设。
- 本地跑之前先 `export` 对应变量（或 `source` `.env`，视 shell 而定）；
  生产用 `docker compose --env-file .env up` 注入。
- 部署 / 脚本里的注释不得再出现明文密码；`deploy.ps1` 早期注释写死过的
  root 密码已被清除；`backend/scripts/t_e2e.py` / `verify-resubmit.py` 的 DB
  密码改读环境变量 `MYSQL_PASSWORD`（经 `backend/scripts/_env.py` 从根目录
  `.env` 加载，值绝不写进源码）。

> 曾发生的泄露：`application-local.yml` 里明文落过 MySQL root 密码、SMTP
> 授权码、LLM key，`deploy.ps1` / `t_e2e.py` 也写死过 root 密码。现已全部
> 改为 `.env` 引用，但**这些旧值仍应作废轮换**（改库密码、重发 SMTP 授权码、
> 平台吊销旧 LLM key），因为它们在 git 历史或本地磁盘里出现过。

### 3. 启动前端

```bash
cd frontend
npm install
npm run dev
```

- 默认 `http://localhost:5173`
- dev 代理:`/api` → `http://localhost:8080`(Vite proxy,避免 CORS)
- 生产:用 `VITE_API_BASE_URL` 注入或 Nginx 反向代理到同域

### 4. 浏览器访问

- 登录/注册:`/login/form`
- 首页 Feed:`/`

---

## 接口约定(遵循通用模板)

- 统一前缀 `/api/`(前端用 `import.meta.env.VITE_API_BASE_URL`,默认 `/api`)
- 统一返回 `{code, message, data}`,`code=200` 成功
- 每张表含通用字段 `id, create_time, update_time, is_deleted`
- 参数化查询(MyBatis-Plus `#{}`),无字符串拼接
- 计数类全部事务内 `SET x = x ± 1`,不读改写
- 幂等接口(点赞/关注/报名)用唯一键 `ON DUPLICATE KEY`,重复操作返 200
- 时间统一 `yyyy-MM-dd HH:mm:ss` 本地时区,前端展示走 `dayjs`,无裸 `toISOString`

## WebSocket 鉴权

浏览器 WS 不能自定义 header,只能 query 带 token:

```
ws://host/ws/chat?access_token=xxx
ws://host/ws/notify?access_token=xxx
```

握手失败(401)→ 前端调 `/auth/refresh` → 重连(指数退避 1→2→4→8→30s)。

## 写扩散 Feed

- 发帖 → `post` 入库(事务 1)→ 查粉丝 → 批量 `INSERT feed_item`(事务 2,500 条/批)
- 游标分页:`idx_fan_time(fan_id, id DESC)`,基于 `last_id`
- 单条扩散失败不影响帖子,记日志兜底(MVP 同步;后续接 MQ + 死信)

## 活动报名防超卖

```
事务内:
  INSERT activity_signup ON DUPLICATE KEY UPDATE id=id   -- 幂等键
  UPDATE activity SET current_count=current_count+1,
    status=IF(current_count+1>=capacity,1,status)
    WHERE status IN (0,1,2) AND current_count<capacity   -- 乐观锁
  影响行=0 → 回滚 signup,返"名额已满"
```

---

## 验证协议(每个模块完成必查)

- [x] 编译/构建通过(`mvn clean compile` ✅ + 前端门禁 10/10 ✅)
- [x] 后端测试**全绿**:`mvn test` → **44 个用例 0 失败**(2026-10-23 修复,此前 19 个失败)
  - CircleAdminServiceTest 19 个:圈名曾因 29 字符撞 3-7 正则全错,已改短名;
    并新增「非目标圈管理不能移帖」负向用例(审查 P0-3)
  - RefreshJtiConcurrency 断言按实际语义修正(并发下恰好 1 个滚动成功、其余全部被拒)
  - 集成测试连本机 MySQL + Redis,CI 里用 service container 跑同一条命令
- [x] schema.sql 实跑验证:MySQL 9.0.1 上建 37 张表,user 28 列,中文注释正常
  - 旧库升级路径同样实跑验证:26 表旧库 → 跑一遍迁移 → 37 表,且第二次执行幂等
- [x] 全局检查调用方同步(接口/VO/DTO 前后端对齐,逐处改齐)
- [x] 过一遍 [AI提示词通用模板.md](AI提示词通用模板.md) 末尾的"坑位自查清单"

### 坑位自查结论

| 自查项 | 结论 |
|---|---|
| 写死 localhost/固定 IP | ✅ 前端 `VITE_API_BASE_URL` 默认 `/api` + Vite proxy;WS 用 `location.host` |
| 日期时区/量纲 | ✅ 前端展示统一 `dayjs`;后端 `DATETIME` 存本地时区,无裸 `toISOString` |
| 百分比/金额换算 | ✅ 媒体大小前后端同标准(7.5M/50M),`before-upload` + 后端双重校验 |
| `@RequestParam`↔query | ✅ 游标 `lastId`/`size` 一律 query;路径 `/{id}` 才用 `@PathVariable` |
| 幂等(计数原子/唯一键) | ✅ `like`/报名/会话用 `ON DUPLICATE KEY` + 唯一键;计数事务内 `SET x=x±1` |
| 写操作幂等 | ✅ 关注/取关/点赞/报名重复操作返 200 不报错 |
| 可运行性(配置本地/生产) | ✅ `application.yml` 全用 `${ENV:默认}`,无本机绝对路径;上传目录相对 `./uploads` |
| 参数化 SQL | ✅ MyBatis-Plus `#{}` + `LIKE` 用 `#{}`,无字符串拼接 |
| WS 鉴权 | ✅ 握手 `?access_token` 验签,401 → refresh → 指数退避重连 |
| Feed 游标 | ✅ `idx_fan_time(fan_id,id DESC)` 基于 `last_id`;前端切 tab `cursor=null` 重置 |
| refresh 重用 | ✅ jti 指纹存 Redis,刷新成功立刻删旧;重用即 401 |
| 活动超卖 | ✅ INSERT 幂等键 + 乐观锁 `UPDATE...WHERE current_count<capacity` 同事务 |
| 冻结账号 | ✅ `status=1` 拒登录/拒发帖;`status=2` 注销数据保留 |

## 已知简化(MVP 取舍,后续可补)

- Feed 扩散用同步写扩散(校园场景粉丝<5000 足够);生产建议接 RabbitMQ/RocketMQ + 死信
- 热度分 MVP 即时 ±1,完整公式 `(like+2*comment)/(age_hours+2)^1.5` 由 HotScoreTask 重算(未实现)
- 搜索为 `LIKE`,量大后上 ES
- 媒体存本地磁盘;生产建议迁对象存储(OSS/COS/MinIO)

## git pre-push 钩子(策略 A:环境类放行、真错拦截)

每次 `git push` 前自动跑 `scripts/run-all-gates.py` 的 9 条门禁,真逻辑错才拦 push,机器环境类失败(OOM/缺命令/超时)只告警放行。

- **激活(一次性)**:`git config core.hooksPath scripts/git-hooks`(钩子本体在 `scripts/git-hooks/pre-push`,受版本控制)。
- **逃生口**:`SKIP_GATES=1 git push …` 跳过门禁直接推(本机 OOM 反复卡手时用)。
- **自测**:`sh scripts/git-hooks/self-test.sh` 证明分流逻辑正确(rc=134→环境不拦、rc=1→拦,不假绿)。
- **已知**:`check-sfc` 在本机 OOM(rc=134)属环境类,正常 push 会放行,不会卡住。
- 与 `docs/ARCHITECTURE.md` TD-10(无 CI)的关系:本钩子是本地 push 前轻量把关,不替代 CI 流水线(`mvn clean package` + `npm run build`),二者互补。

