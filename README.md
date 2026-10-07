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
│  │  ├─ entity/       PO(11 张表)
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
│  └─ schema.sql       11 张表建库建表(含通用字段+状态枚举+索引)
└─ .gitignore
```

---

## 快速开始

### 1. 准备环境

- JDK 17+
- MySQL 8.x(创建 `campus_social_v2` 库,跑 [sql/schema.sql](sql/schema.sql))
- Redis 6+(默认 `127.0.0.1:6379`)
- Node 18+

### 2. 启动后端

```bash
cd backend
# 配置 MySQL/Redis 连接(或用环境变量覆盖)
mvn spring-boot:run
```

关键配置在 `backend/src/main/resources/application.yml`:
- `MYSQL_HOST` / `MYSQL_USER` / `MYSQL_PASSWORD`(默认 127.0.0.1/root/root)
- `REDIS_HOST` / `REDIS_PORT`
- `JWT_ACCESS_SECRET` / `JWT_REFRESH_SECRET`(至少 64 字节,生产用强随机)
- `UPLOAD_DIR`(默认 ./uploads,相对路径,不写本机绝对路径)

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

- [x] 编译/构建通过(`mvn clean compile` ✅ + `npm run build` ✅)
- [ ] 实际跑过一次(注册 → 登录 → 发帖 → Feed → 点赞 → 私信 → 活动报名)
  - ⚠️ 本机未装 MySQL/Redis,运行时闭环**未实际启动验证**(缺依赖,非代码问题)
  - 跑通步骤:先 `mysql < sql/schema.sql` + 起 Redis,再 `mvn spring-boot:run` + `npm run dev`
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
