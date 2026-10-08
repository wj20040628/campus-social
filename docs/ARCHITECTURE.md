# 友声 · 校园生活社区 —— 技术架构文档

> 文档状态：Phase 1 交付 / 已裁决项以 ADR 为准 / 未决项见 `decisions/OPEN-DECISIONS.md`
> 编写：高见远（架构） · 日期：2026-10-08 · 基线：磁盘实测（非假设），实测方法见 §2

---

## 0. 结论摘要

| 项 | 结论 |
|---|---|
| 技术栈 | 沿用现有 **Vue 3.5 + Element Plus 2.14 + SpringBoot 3.2.5 + Java 17 + MyBatis-Plus + MySQL 8.4 LTS + Redis**，不换语言、不换框架 |
| 改造方式 | **增量复用**，不重写（ADR-001） |
| 部署 | **云服务器单机直装** JDK17 + Nginx + MySQL 8.4 LTS + Redis 7（ADR-002），不做容器化、不用托管 Serverless |
| 月成本 | **¥40/月（保守价） / ¥25/月（新人首年价）**，含域名，备案 ¥0 但占 5–20 个工作日 |
| 短信 | **MVP 不做**。个人主体已无法申请自用短信签名（腾讯云 2025-09-18 起停止新增个人自用资质）。改用**邮箱验证码**；若必须短信，唯一低成本路径是阿里云「号码认证-短信认证」（个人可开，1000 次/年 ¥39），代价是**签名是平台赠送的通用签名，短信里看不到"友声"两个字**（ADR-006） |
| 支付 | **MVP 完全不做**，写进 Out-of-Scope（ADR-007）。微信支付线上场景需营业执照主体，小微商户有收款限额且多数线上支付场景不开放 |
| 图标 | 全项目锁定 **`@element-plus/icons-vue` 2.3.2**，尺寸 16/20/24px，禁混用（ADR-003） |
| WebSocket | **MVP 不引入 MQ**。单机内存广播足够，临界点 **同时在线长连接 > 5000 或 部署第 2 个后端实例**（ADR-004） |
| 移动端响应式（PRD RICE 第一） | **纯 CSS 断点改造 + 自研 3 个移动端组件，不引入 Vant**。断点/间距/字号全部引用设计师令牌。工作量 **全量 14 / 后台页桌面 12.5 / 只做兜底 2 人日**；架构建议：**要移动优先就选 14，先做网页就选 2，不要选中间档**（§3 Q8） |
| 设计令牌 | `docs/design/design-tokens.css` 为**唯一准绳**，`frontend/src/styles/tokens.css` 降级为待迁移，Phase 3 第一个 PR 删除重定义并改为 import（ADR-009） |

**Top 3 技术风险**

1. **Redis 5.0 指令集天花板** —— 本地 Redis 5.0.14.1 不支持 6.2 的 `GETDEL`（已踩坑）。所有"原子读删"必须走 Lua。新写的限频/配额代码极易再踩。
2. **MySQL 9.0 是已 EOL 的 Innovation 版（2024-10-15 停止支持）**，而生产必须是 8.4 LTS。**9.0 → 8.4 是降级，官方要求逻辑导出再导入**，不是原地升级。
3. **WS 单机上限** —— 内存 Session 无法跨实例。一旦要横向扩容或在线破 5000，必须改造成 Redis PubSub 广播，否则会出现"一半用户收不到消息"。

---

## 1. 目标与非目标

### 1.1 目标
面向在校大学生的校园社交信息一站式平台。首批三个场景：
- 不用加微信/QQ 好友就能搭话（陌生人破冰私信）
- 按兴趣找搭子（发现/推荐列表）
- 学校通知一处看全（官方公告一站触达）

Web 优先，App 后置，小程序本轮不做。必须真跑起来给本校同学用。

### 1.2 Out-of-Scope（本轮明确不做）
- 支付（任何形式）—— 见 ADR-007
- 短信验证码 —— 见 ADR-006（v1.1 再评估）
- App / 小程序 / 多校多租户
- 推荐算法（协同过滤/向量召回）—— MVP 用标签命中 + 时间倒序的确定性排序
- 对象存储迁移（OSS/COS）—— v1.2，本轮仍存本地磁盘并做备份（已知风险，见 TD-06）
- 消息队列（RabbitMQ/RocketMQ/Kafka）
- 微服务拆分、服务网格、K8s

---

## 2. 现状评估（磁盘实测基线）

实测路径：`C:\Users\Administrator\Desktop\校园社交`，git 分支 `master`，HEAD `ec688ea`。

### 2.1 已具备能力（可直接复用）

| 能力 | 证据 |
|---|---|
| SpringBoot 3.2.5 / Java 17 / Maven 单模块 `com.campus.social` | `backend/pom.xml:9-30` |
| 12 个 Controller（Auth/User/Relation/Post/Chat/Group/Circle/Notify/Search/Review/Audit/Upload） | `backend/src/main/java/com/campus/social/controller/` |
| 23 张表 | `sql/schema.sql` 实测枚举：activity, activity_signup, audit_log, chat_conversation, chat_message, circle, circle_member, comment, feed_item, group, group_member, group_message, group_read, like, notify, post, post_topic, relation, review_task, sensitive_word, topic, user |
| JWT 双 token + refresh jti 吊销 | `application.yml:67-74`，`AuthInterceptor` |
| Spring 原生 WebSocket（/ws/chat、/ws/notify）+ 按 userId 多 session 广播 | `WebSocketConfig.java:26-31`，`WsSessionManager.java:22-52` |
| 敏感词 DFA 过滤 | `util/SensitiveWordFilter.java` |
| 管理员角色校验 `requireAdmin` | `RoleServiceImpl.java:30-35` |
| 审核流（ReviewTask / AuditLog） | `ReviewService`、`AuditService` |
| 邮箱验证码（SMTP，dev 模式控制台打印） | `SmtpMessageGateway`、`DevModeMessageGateway`、`VerifyCodeServiceImpl` |
| 中文全文索引（ngram） | `sql/migration_c3_post_fulltext.sql` |
| 前端 16 个页面 + 设计 token 文件 | `frontend/src/views/`，`frontend/src/styles/tokens.css` |
| 生产编排与 Nginx 反代（/api、/ws、/uploads） | `docker-compose.yml`，`frontend/nginx.conf` |

### 2.2 技术债清单（分级）

**D1 —— 上线前必须修（阻断放行）**

| ID | 问题 | 证据 | 修复方向 |
|---|---|---|---|
| TD-01 | CORS 全开 `allowedOriginPatterns("*")` | `WebMvcConfig.java:59-66` | 收窄为实际域名白名单，从环境变量注入 |
| TD-02 | WebSocket `setAllowedOrigins("*")` | `WebSocketConfig.java:29` | 同上，收窄为具体 origin |
| TD-03 | 对外 URL 无版本前缀：后端路由是 `/auth/login`，靠 Nginx rewrite 剥 `/api` | `AuthController.java:20` + `nginx.conf` 的 `rewrite ^/api/(.*)$ /$1` | 对外契约统一 `/api/v1/*`，由 Nginx 做版本路由（见 ADR-008 说明的不完美性与取舍） |
| TD-04 | 本地 Redis 5.0.14.1 与 `docker-compose.yml` 的 `redis:7-alpine` 版本分裂 | `docker-compose.yml:36` | 代码按 5.0 语义写（最严基线），生产跑 7.x 向下兼容。禁用清单见 §6.3 |
| TD-05 | 本地 MySQL 9.0（Innovation，**EOL 2024-10-15**），生产需 8.4 LTS；9.0→8.4 属降级，官方要求逻辑 dump+load | `F:\MySQL\MySQL Server 9.0`；MySQL 官方发布模型文档 | 本地加装 MySQL 8.4 LTS 实例（端口 3307），dev 与生产同主版本 |
| TD-06 | 上传文件存本地磁盘 `./uploads`，服务器重建即丢，且无法水平扩容 | `application.yml:78` | MVP 接受，但必须做每日异地备份；v1.2 迁对象存储 |
| TD-07 | 前端出现 emoji 字符当功能图标 | `frontend/src/views/Chat.vue:150`、`frontend/src/views/Notify.vue:103` | 换成 `@element-plus/icons-vue` 组件 |
| TD-08 | 前端出现硬编码颜色值 | `frontend/src/views/Login.vue:171`、`Register.vue:148`、`Chat.vue:1027`（`styles/tokens.css:26` 是 token 定义处，合规） | 全部改为引用 `tokens.css` 的变量 |
| TD-09 | 无健康检查端点（无 actuator），Nginx/运维无法探活 | `backend/pom.xml` 无 actuator 依赖 | 加 `spring-boot-starter-actuator`，只暴露 `/actuator/health` 且不对公网开放 |
| TD-10 | 无 CI：无 GitHub Actions / 无构建门禁 | `.github/` 仅有 modernization 脚本 | 至少加一条 `mvn -B clean package` + `npm run build` 的流水线 |
| TD-21 | **Element Plus 按需引入被全量引入架空**：`main.js` 全量注册 + `vite.config.js` 同时配 unplugin 按需 + `manualChunks` 强制整包成 chunk | `main.js:3-6`、`vite.config.js:20-35` | 删 `main.js` 的 `app.use(ElementPlus)` 与 `element-plus/dist/index.css`；`manualChunks` 去掉 `element-plus` 项。0.3 人日。**不修则移动端首屏预算不可能达标**（见 §3 Q8.8） |
| TD-22 | 前端**零响应式**：18 个 SFC 共 5172 行，无一处 `@media` | `grep -l "@media" frontend/src/**/*.vue` 无结果 | 见 §3 Q8 完整方案 |
| TD-23 | `100vh` 在移动端被地址栏遮挡 | `Chat.vue:695`、`Login.vue:167`、`Register.vue:144`、`MainLayout.vue:139,142` | 统一改 `100dvh` |
| TD-24 | `index.html` 的 `theme-color` 与设计师主色不一致（Token 双源第 3 个实例） | `frontend/index.html:8` | 改为引用 `design-tokens.json` 的 `color.accent` |

**D2 —— MVP 内应修（影响可用性/体验）**

| ID | 问题 | 证据 | 说明 |
|---|---|---|---|
| TD-11 | `README.md` 声明"Redis 6+"，实际本地 5.0 | `README.md:70` | 文档与事实不符，会误导后续开发 |
| TD-12 | Element Plus 声明 `^2.8.0`，lock 实际解析到 2.14.5 | `package.json:14` vs `package-lock.json` | caret 会静默升小版本，EP 小版本有 UI 回归史 → 锁精确版本 |
| TD-13 | 无统一 API 错误码文档（只有 `ResultCode` 枚举） | `common/ResultCode.java` | Phase 2 输出 `openapi.yaml` 时补齐 |
| TD-14 | Feed 写扩散同步执行，粉丝量大时发帖接口变慢 | `README.md:129-131` | 校园场景（粉丝 < 5000）可接受；监控发帖 P95 |
| TD-15 | 搜索为 `LIKE`，已有 ngram FULLTEXT 但未确认已接入查询路径 | `migration_c3_post_fulltext.sql` 建了索引，Java 侧未见 `MATCH AGAINST` | 需确认是否已生效，否则索引白建 |

**D3 —— 已知简化，本轮接受**

| ID | 问题 | 说明 |
|---|---|---|
| TD-16 | 热度分 HotScoreTask 未实现 | `README.md:176` |
| TD-17 | 无分布式锁/幂等框架，靠唯一键 + 乐观锁 | 单机场景够用 |
| TD-18 | 无监控告警 | v1.2 补 |
| TD-19 | 会话创建用 `upsertConversation` + `SELECT LAST_INSERT_ID()` 两步 | `ChatConversationMapper.java:47-53`。两步在同一连接内执行，`LAST_INSERT_ID()` 是连接级语义，当前正确；但**任何把它换成连接池复用/异步执行都会炸**，加注释锁死 |

---

## 3. Q1–Q7 逐条结论

### Q1 复用 vs 重写

**裁决：增量复用。**

| 维度 | 增量复用 | 推倒重写 | 权重 |
|---|---|---|---|
| 交付周期 | 3 个缺口约 6–9 人日 | 存量能力重建 25–35 人日 | 高 |
| 用户技术栈匹配 | Java SpringBoot，完全在舒适区 | 若换 Node/Python 后端，用户不会 | **决定性** |
| 真实校园推广时效 | 快，能赶上本学期 | 慢，错过推广窗口 | 高 |
| 毕设复用 | 现有 23 表 + 12 Controller 是现成素材 | 需重做 | 高 |
| 技术债 | 承接 D1 共 10 项，需专项修 | 债务归零，但重写本身引入新债 | 中 |
| 架构天花板 | 单模块，未来拆分有成本 | 可按新架构设计 | 低（MVP 不需要） |

**理由**：用户只会 Java SpringBoot，这是硬约束不是偏好。重写若换技术栈等于让用户边学边写，MVP 交付风险不可控；若不换技术栈，则"重写"只是把同样的代码再写一遍，价值为负。现有工程的骨架（分层、鉴权、WS、审核、敏感词）是完整且正确的，缺的是三个垂类场景和一批上线前必修项。

**复用的代价（必须承认）**：承接 10 项 D1 债务；单模块 Maven 工程在代码量到 5 万行后编译与定位变慢；`README.md` 与实际代码已有偏差（TD-11），后续维护要靠文档同步纪律。

→ 详见 `decisions/ADR-001.md`

### Q2 部署路径

**裁决：方案 A —— 云服务器单机直装。**

| 维度 | A 单机直装 | B 服务器 Docker | C 托管 Serverless（CloudBase 等） |
|---|---|---|---|
| 上手成本 | 低：apt 装 4 个包，全是 Linux 通用技能 | 中：需学 compose 网络/卷/构建上下文，约 1–2 天 | 高：需懂 Node 生态、云函数冷启动、厂商控制台 |
| 与用户能力匹配 | 完全匹配（Linux 命令 + Java） | 部分匹配 | **不匹配 —— 用户不懂 Node 生态** |
| 本地可复现性 | 高：本机直装 MySQL/Redis，与服务器同构 | **低**：本机 Windows 无 Docker Desktop，本地永远跑不了 compose，本地/生产环境必然不一致 | **极低**：本地根本无法完整复现云函数运行时 |
| 供应商锁定 | 无，换云厂商重装即可 | 弱（Docker 是标准） | **强**：绑定厂商 SDK、数据库格式、计费模型，迁移要重写接入层 |
| 排障难度 | 低：`journalctl` / `tail -f` 直接看日志 | 中：多一层容器抽象 | 高：日志在厂商控制台，本地无法复现调试 |
| 资源开销 | 最低（无容器层） | 低 | SpringBoot 大包在云函数冷启动 3–8s，体验差 |
| 月成本 | ¥25–40 | ¥25–40（同配置） | ¥100–300（Java 云托管最低配）+ 数据库另计 |
| 未来扩容 | 需手工重复搭建 | 一条命令扩实例 | 厂商托管 |

**关键澄清（用户关心的矛盾点）**：
"本机装不了 Docker Desktop" 与 "服务器用 Docker" **不冲突** —— Windows 10 21H1 装不了 Docker Desktop 只约束**本地开发环境**，服务器是 Linux，装 Docker 完全没问题。所谓"分裂策略"（本地直装 + 服务器容器化）技术上可行。

**但我不推荐**，理由不是技术可行性，而是**风险收益比**：
1. 本地永远跑不了 `docker-compose.yml`，意味着这个文件**无人验证**。它现在是"看起来对"的状态（`docker-compose.yml:36` 的 `redis:7-alpine` 与本地 5.0 已经分裂了），真到服务器上一跑，出问题排查的是你从没在本地见过的组合。
2. 用户只有一个人。多一层抽象 = 多一份排查负担，而收益（未来扩容便利）在 MVP 阶段为零。
3. Dockerfile 里的 `JAVA_TOOL_OPTIONS: "-Xms256m -Xmx512m"`（`docker-compose.yml:72`）说明目标是 2G 内存学生机，这个内存量下容器化不带来任何收益。

**成本估算（人民币）**

| 项目 | 保守价（续费/非活动） | 新人首年价 | 说明 |
|---|---|---|---|
| 云服务器 2核4G（轻量） | ¥31.6/月（¥379/年，阿里云非活动价） | ¥16.6/月（¥199/年） | 推荐 2核4G 起步：JVM 1.5G + MySQL 1G + Redis 300M + 系统 |
| 域名 | ¥7.5/月（¥90/年） | ¥3–7/月（首年活动） | .cn/.com，续费会涨 |
| SSL 证书 | ¥0 | ¥0 | 云厂商免费 DV 证书或 Let's Encrypt |
| ICP 备案 | ¥0 | ¥0 | **但需 5–20 个工作日管局审核，这是时间成本不是钱** |
| 邮箱验证码（SMTP） | ¥0 | ¥0 | QQ 邮箱授权码免费 |
| 短信（可选，非 MVP） | +¥3.25/月（1000次/年 ¥39） | 同 | 阿里云号码认证-短信认证 |
| **合计** | **约 ¥40/月** | **约 ¥25/月** | |

**隐性代价（诚实披露）**：
- **新人价只有第一年**，第二年续费通常翻倍。做预算按保守价 ¥40/月 规划。
- **备案是硬门槛**：域名未完成 ICP 备案前，80/443 端口会被云厂商拦截，服务无法通过域名访问。备案需域名实名认证 + 网站负责人核验 + 管局审核，**实际周期 5–20 个工作日，且可能被打回补材料**。这意味着"买服务器 → 上线"中间有一段不可压缩的等待期。
- 单机部署 = **单点故障**。服务器宕机 = 服务全停。MVP 接受，但必须做每日数据库异地备份（见 §8）。
- 2核4G 下 MySQL + Redis + JVM 挤在一台机器上，内存紧张。JVM 堆建议 `-Xms768m -Xmx1280m`，别照抄容器里的 512m（那是给 2G 机用的）。

→ 详见 `decisions/ADR-002.md`

### Q3 短信验证码：MVP 不做

**查证结论（有据可查，非推测）**：

| 厂商 | 个人主体能否申请签名 | 依据 |
|---|---|---|
| 腾讯云 短信 SMS | **不能（自用）**。2025-09-18 起"不再支持新增个人认证自用资质"；存量个人自用资质不可修改、不可再关联新签名。API 层直接报 `FailedOperation.NotEnterpriseCertification` | 腾讯云公告 `cloud.tencent.com/announce/detail/2127`；实名资质审核标准 `cloud.tencent.com/document/api/236/108274` |
| 阿里云 短信服务 SMS | **不能（自用）**。个人认证无法通过运营商签名实名报备。可用"他用"资质（企业证件 + 授权委托书） | 阿里云帮助中心 `help.aliyun.com/document_detail/108073.html` |
| 阿里云 号码认证-短信认证 | **可以**。个人实名认证即可，免资质、免签名报备，用平台赠送签名与模板，仅支持验证码类 | 阿里云帮助中心 `help.aliyun.com/zh/document_detail/3000317.html` |

**价格与周期（阿里云号码认证-短信认证，2025-09-26 调价后）**：
- 套餐包：100次 ¥5.4 / 1000次 ¥54 / 5000次 ¥260 / 1万次 ¥500（均为一年期；活动价曾低至 100次 ¥3.99/年、1000次 ¥39/年）
- 折合单价：**¥0.045–0.054/条**
- 审核周期：传统短信服务签名审核约 2 小时，但**运营商实名报备另需 7–10 个工作日**（厂商明确未承诺时效）。号码认证-短信认证因免签名报备，可**当天开通**。

**裁决：MVP 用邮箱验证码，短信延后到 v1.1。**

**这个决定的代价（必须说清）**：
1. **校园场景下手机号比邮箱自然得多**。大学生有手机号、未必有查邮箱的习惯。注册转化率会明显低于短信验证码方案。这是产品层面的真实损失。
2. **邮箱通道有发送量上限**。QQ 邮箱未公开 SMTP 日发送上限，社区实测在数十至数百封量级，超限会触发临时限流甚至封停 SMTP 权限。首批若几百人集中注册，有打爆风险。→ 列入 OPEN-DECISIONS，需实测验证。
3. **验证码邮件进垃圾箱**是常态，尤其 QQ 邮箱对个人 SMTP 发件。

**降低代价的做法**：
- 主通道保留邮箱验证码（已有实现，零成本）
- 同时在注册页做**手机号 + 密码**注册（已实现，`AuthController` 有手机号注册路径），把"验证码"从必经路径降级为可选路径
- 准备好短信的接入抽象：`MessageGateway` 接口已存在（`SmtpMessageGateway` / `DevModeMessageGateway`），v1.1 加一个 `AliyunDypnsGateway` 实现即可，改动面可控在 1 个类

**v1.1 上短信的前置动作**：阿里云账号个人实名认证 → 开通号码认证服务 → 控制台开启"短信认证"开关 → 用 `dypnsapi20170525` SDK（注意：不是传统的 `dysmsapi20170525`）。预期 0.5 人日。

→ 详见 `decisions/ADR-006.md`

### Q4 支付：MVP 完全不做

**裁决：不做，写入 Out-of-Scope。**

理由：MVP 三个场景（破冰私信、找搭子、看通知）没有任何一个需要资金流转。加支付 = 引入合规主体要求 + 对账 + 退款 + 资金安全，对一个还没验证需求的产品是纯负担。

**v2 接入微信支付的前置条件（让用户心里有数）**：

| 前置项 | 要求 | 难度 |
|---|---|---|
| 经营主体 | 微信支付主体类型分 小微 / 个体工商户 / 企业 等。小微可用身份证 + 结算银行卡申请，但**收款限额低**（如信用卡日累计约 ¥1000） | 中 |
| 线上支付场景 | PC 网站支付、JSAPI 等线上场景**通常不向小微开放**，需个体工商户或企业营业执照 | **高 —— 这是真正的门槛** |
| 已备案域名 | 微信支付要求经营场景提供已备案域名；PC 网站场景需提交网站备案信息 | 中（备案已在做） |
| 结算账户 | 企业须对公账户；个体工商户可选法人个人储蓄卡 | 低 |
| 审核周期 | 资料审核 1–3 个工作日 + 签约审核 1–2 个工作日 | 低 |
| 费用 | 无开户费/年费；交易费率约 0.6%（服务商渠道可谈到 0.2%） | — |

**一句话给用户**：微信支付不卡在技术上，卡在**主体资质**。个人身份最多拿到小微商户（限额、且多数线上场景不给开），要做到真正的线上收款，得先注册个体工商户或公司。这是 v2 的事，现在别花心思。

→ 详见 `decisions/ADR-007.md`

### Q5 WebSocket 单机 vs 集群

**裁决：MVP 保持单机内存广播，不引入消息队列/Redis PubSub。**

**现状**：`WsSessionManager` 用 `ConcurrentHashMap<Long, Set<WebSocketSession>>` 存本机 session（实测 `WsSessionManager.java:22-23`），`WsPushService` 直接本机广播。单实例下完全正确。

**容量测算依据**（不是拍脑袋）：
- 每个 WS 长连接服务端开销约 50–100KB（Socket 收发缓冲 + Session 对象 + 用户上下文），连接本身不独占线程
- Tomcat 默认 `maxConnections=8192`、`maxThreads=200`；NIO 模式下连接不占线程，但消息处理仍走线程池
- 2核4G 机器，JVM 堆给 1.28G：理论 1.3 万–2 万连接，但 **CPU 会先于内存成为瓶颈**（心跳 + 上下行报文处理）

**明确的临界点（分档，按此执行）**

| 阶段 | 同时在线 WS 连接 | 做法 | 工作量 |
|---|---|---|---|
| 阶段 0（MVP 上线） | < 1,000 | 现状不动 | 0 |
| 阶段 1 | 1,000 – 5,000 | 单机 + 参数调优：`server.tomcat.max-connections=10000`、`max-threads=400`、`ulimit -n 65535`、`net.core.somaxconn=65535`，开启 WS 心跳与死连接清理 | 0.5 人日 |
| **阶段 2（临界点）** | **> 5,000** 或 **部署第 2 个后端实例** | **必须**引入 Redis PubSub 广播：`WsPushService` 改为 `convertAndSend`，每实例订阅并只转发本机命中的 userId | 1 人日 |
| 阶段 3 | > 50,000 | 换 Netty/WebFlux 或独立 IM 服务 | 3–5 人日 |

**临界点数字（记住这两个，满足任一即升级）**：
1. **峰值同时在线长连接 > 5,000**
2. **后端实例数 ≥ 2**（哪怕只有 2 个实例、每个 500 连接，内存 Session 也会导致跨实例消息丢失）

换算成业务量：按校园场景同时在线率约 15–25% 估算，**日活超过 2 万**才需要动这个。MVP 目标（本校几千人）远未触及。

**为什么现在不做**：引入 PubSub 会让每条消息多一跳 Redis，增加延迟与故障面；而 `WsPushService` 的接口边界已经很清晰（推送入口单一），改造点集中，等到临界点再改成本很低。**现在做就是过度设计。**

**但需要现在做的一件事**：在 `WsPushService` 的 Javadoc 里写明"本机广播，多实例部署前必须改造"，防止半年后横向扩容时踩坑。→ TD-20，列入 D2。

→ 详见 `decisions/ADR-004.md`

### Q6 三个缺口的增量方案（草案粒度）

#### G1 陌生人破冰私信

**现状盘点（好消息）**：`ChatServiceImpl.send()` **已经不校验好友关系**（实测 `ChatServiceImpl.java:91-143`，只校验了"不能给自己发"和"对方存在"）。所以"免好友发起会话"能力**已存在**。真正缺的是配套的护栏。

**新增表**

| 表 | 字段要点 | 索引 |
|---|---|---|
| `report` 举报 | `id, reporter_id, target_type(1用户 2帖子 3评论 4聊天消息 5搭子), target_id, reason_type(1骚扰 2广告 3色情 4诈骗 5其他), reason_text, status(0待审 1成立 2驳回), handler_id, handle_time` + 通用 5 字段 | `uk_report(reporter_id, target_type, target_id)` 防重复举报；`idx_status_time(status, create_time DESC)` |

**频控不加表**：用 Redis ZSET 滑动窗口（Lua 脚本，见 §6.3 第 3 条），避免为频控引入持久表。
**审核复用**：举报处置**不进** `review_task`，在新建的 `report` 表闭环（`report.status` 即处置回执）。**勘误**：本行原文写「举报进入现有 review_task 流」，经复核不可行——`review_task` 是帖子专用队列（`schema.sql:303-315` 的 `post_id NOT NULL` + `uk_post`，`ReviewService.java:16-27` 的方法语义全是 post），而举报的 `target_type` 有 5 种。详见 §3 Q9.4 的纠正表。

**新增/改造端点**

```
POST   /api/v1/report                       提交举报（幂等，同目标重复提交返回已记录）
GET    /api/v1/admin/reports?status=&cursor= 举报列表（管理员）
POST   /api/v1/admin/reports/{id}/handle    处理举报（封禁/驳回/忽略）

POST   /api/v1/chat/send                    【改造】增加三重护栏：
                                            1. 黑名单双向拦截（relation.type=3）
                                            2. 陌生人日配额（Redis 滑动窗口，超限返 429 + 明确文案）
                                            3. 敏感词过滤（复用 SensitiveWordFilter）
GET    /api/v1/chat/stranger-quota          查询今日剩余破冰额度（前端展示，避免用户盲目试错）
```

**配额建议值**：`stranger.daily-new-peer = 5`（每天最多向 5 个新对象发起首条消息）、`stranger.per-peer-daily = 20`（对同一陌生人每天最多 20 条）。配置项进 `application.yml`，不硬编码。

**必须做的产品护栏（技术之外的关键）**：接收方需要"不接受陌生人消息"开关。加 `user.stranger_msg_enabled TINYINT DEFAULT 1` 字段，关闭后陌生人发送直接返"对方已关闭陌生人消息"。没有这个开关，破冰功能上线即变成骚扰工具。

#### G2 按兴趣标签找搭子

**现状盘点**：有 `topic`（帖子话题，随帖子暴涨，不适合做标签字典）、有 `circle`（圈子，需加入，太重）。缺"用户兴趣标签"和"搭子卡片"两层。

**新增表**

| 表 | 字段要点 | 索引 |
|---|---|---|
| `interest_tag` 标签字典 | `id, name, category(1兴趣 2游戏 3运动 4学习 5生活), sort, use_count` + 通用字段 | `uk_name(name)`；人工维护 30–60 个，不做 UGC |
| `user_tag` 用户-标签 | `id, user_id, tag_id` + 通用字段 | `uk_user_tag(user_id, tag_id)`；`idx_tag(tag_id)` |
| `buddy_post` 搭子卡片 | `id, user_id, tag_id, title, content, want_count, joined_count, status(0招募中 1已满 2已关闭 3下架), expire_time` + 通用字段 | `idx_tag_status_time(tag_id, status, create_time DESC)`；`idx_user(user_id)` |
| `buddy_join` 报名 | `id, buddy_id, user_id, status(0待确认 1已加入 2已拒绝 3已退出)` + 通用字段 | `uk_buddy_user(buddy_id, user_id)` 幂等；`idx_user(user_id, status)` |

**为什么 `buddy_post` 不复用 `activity`**：`activity` 有报名截止时间 + 活动状态机 + 线下场地语义，搭子卡片是轻量招募（"今晚王者缺一个"），语义不同。硬塞进 `activity` 会让两张表都变脏。**但报名防超卖逻辑必须复用** —— 照抄 `activity_signup` 的"INSERT 幂等键 + `UPDATE ... WHERE joined_count < want_count` 乐观锁"同事务写法。

**新增端点**

```
GET    /api/v1/tags                         兴趣标签树（按 category 分组）
PUT    /api/v1/user/tags                    设置我的兴趣标签（批量覆盖，幂等，最多 8 个）
GET    /api/v1/user/{id}/tags               查看他人标签
POST   /api/v1/buddy                        发布搭子卡片
GET    /api/v1/buddy?tagId=&status=&cursor=&size=   发现/推荐列表
GET    /api/v1/buddy/{id}                   详情
POST   /api/v1/buddy/{id}/join              报名（幂等 + 乐观锁防超卖）
DELETE /api/v1/buddy/{id}/join              取消报名
GET    /api/v1/buddy/mine?type=published|joined     我发布的 / 我报名的
PATCH  /api/v1/buddy/{id}/status            关闭/重新开启
```

**推荐排序（MVP 确定性算法，不上算法）**：
1. 命中的共同标签数降序
2. 同校（MVP 单校，此维度恒等，预留）
3. `create_time` 降序
不做协同过滤、不做向量召回。理由：数据量小的时候算法输出的质量不如规则，且无法解释。

#### G3 官方公告一站触达

**现状盘点**：`notify` 是**用户级事件通知**（type 1关注 2赞 3评 4私信 5活动），每条 notify 属于一个 user。公告是**广播级内容**，需要"一份内容 + 全员可见 + 已读回执"，用 `notify` 表达会导致一人一条的大表爆炸（1 万用户 × 100 公告 = 100 万行）。必须新建实体。

**新增表**

| 表 | 字段要点 | 索引 |
|---|---|---|
| `announcement` | `id, title, content, category(1教务 2后勤 3活动 4社团 5紧急 6其他), publisher_id, school_id(默认 1，预留多校), is_top(0/1), top_expire_time, status(0草稿 1已发布 2已下架), publish_time, read_count` + 通用字段 | `idx_school_status_top_time(school_id, status, is_top DESC, publish_time DESC)`；`idx_category(category, status, publish_time DESC)` |
| `announcement_read` | `id, announcement_id, user_id, read_time` | `uk_ann_user(announcement_id, user_id)` 幂等已读；`idx_user(user_id, read_time)` |

**为什么不用"一人一条 notify"**：写入放大 1 万倍，且已读状态更新要 UPDATE 1 万行。用 `announcement_read` 只在实际有人读时写一行，"未读数 = 已发布总数 − 本人已读数"，用 count 查询而非全量写入。

**新增端点**

```
POST   /api/v1/admin/announcement            发布公告（草稿 / 直接发布）
PATCH  /api/v1/admin/announcement/{id}       编辑（含置顶/取消置顶/上/下架）
GET    /api/v1/admin/announcement?status=&cursor=  后台列表
GET    /api/v1/announcement?category=&cursor=&size= 前台列表（置顶优先）
GET    /api/v1/announcement/{id}             详情（返回 is_read）
POST   /api/v1/announcement/{id}/read        已读回执（幂等）
GET    /api/v1/announcement/unread-count     未读公告数（红点）
```

**推送策略**（避免过度设计）：
- 发布时**不**给全员写 notify 行
- 通过 `WsPushService` 向**当前在线**用户推一条轻量提示（新增 `notify.type = 6 公告`），内容是标题 + id，前端弹一个角标
- 离线用户靠 `unread-count` 红点拉取
- 置顶公告在列表接口自然排序，不做额外推送

**权限**：所有 `/admin/**` 端点第一行调 `roleService.requireAdmin(userId)`（已实现，`RoleServiceImpl.java:31-35`）。

### Q7 图标库选型

**裁决：锁定 `@element-plus/icons-vue`，精确版本 `2.3.2`。不引入 lucide 或任何第二套图标库。**

**依据（实测）**：

| 项 | 结论 | 证据 |
|---|---|---|
| 是否已在依赖树 | **是**。`element-plus@2.14.5` 把它作为直接依赖传递进来 | `package-lock.json:1421` |
| 是否已在用 | **是**，11 个文件已 import | `PostCard.vue:61`、`MainLayout.vue:75`、`ActivityDetail.vue:56`、`Chat.vue:182`、`CircleDetail.vue:144`、`Circles.vue:66`、`Login.vue:67`、`PostDetail.vue:119`、`Profile.vue:113`、`Publish.vue:79`、`Register.vue:54` |
| 图标数量 | **294 个**（设计师实测，非估算） | `docs/design/design-tokens.json` 的 `icon.totalIcons` |
| 风格契约 | `viewBox="0 0 1024 1024"` + `fill="currentColor"`（**填充型**，非描边型） | 同一文件 `icon.style` |

**裁决理由（4 条）**：

1. **零新增体积**：已是传递依赖，已在打包产物里。换 lucide-vue-next 是净增。
2. **视觉一致性是硬约束，不是审美偏好**：Element Plus 组件内部（输入框清除叉、下拉箭头、日期选择器日历、消息弹窗、分页器箭头）已经在用这套图标。若业务代码用 lucide（描边型、24 viewBox），同一页面会同时出现填充型与描边型两套视觉语言 —— 这是"混用"最典型的翻车现场，而混用恰恰是 P0 规则明令禁止的。
3. **尺寸与风格体系天然对齐**：EP icons 与 EP 组件内图标同源（1024 viewBox 填充型），不需要额外的换算层或视觉调和层。
4. **学习成本为零**：用户和团队已在用，不需要再记一套命名（`ChatDotRound` vs `message-circle`）。

> 设计师在 `docs/design/design-tokens.json` 中独立得出同一结论（同库、同版本 2.3.2、同尺寸档位 16/20/24），本裁决与该令牌文件互为准绳。

**代价（诚实说）**：
- EP icons 是"够用"而非"精美"，风格偏中性，做不出有个性的品牌感
- **294 个图标存在明确缺口**：点赞（无 thumbs-up）、审核/管理（无 shield）、发送（无 send）三个本产品核心语义都缺失
- 若 v2 要做品牌化视觉升级，届时应**整体替换**而非混用 —— 替换成本约 1–2 人日（11 个文件的 import + 全局注册 + 3 个自绘图标）

**自绘补齐（不构成混用）**：允许在 `src/icons/` 下自绘 3 个图标 —— `IconLike.vue`（点赞，空/实两态）、`IconShield.vue`（管理审核）、`IconSend.vue`（发送纸飞机）。**必须遵循同一风格契约**（`viewBox="0 0 1024 1024"` + `fill="currentColor"`），否则视为引入第二套图标库。

**执行约束（写进 Spec 的硬规则）**：
- 全项目图标仅来自两处：`import { X } from '@element-plus/icons-vue'`，或 `src/icons/` 下经设计评审的自绘图标
- 尺寸只允许 **16 / 20 / 24** 三档，走设计令牌的 CSS 变量，禁止在模板里写死像素
- 禁止任何 emoji 字符当功能图标（违反 P0）。当前违规点：`Chat.vue:150`、`Notify.vue:103`
- 需要新图标时，**提需求给设计评审**，不得私自引入第二套图标库

**必须做的动作**：`@element-plus/icons-vue` 当前是**传递依赖**（靠 element-plus 带进来）。一旦 element-plus 升级或改依赖结构，它会消失。必须把它**提升为 `package.json` 的直接依赖**并锁 `2.3.2`。

→ 详见 `decisions/ADR-003.md`

### Q8 移动端优先响应式改造（PRD RICE 第一，架构侧落地方案）

> 本节回应 PM 的 RICE 第一项。所有断点、间距、字号**一律引用设计师令牌**（`docs/design/UIUX.md` §6 与 `docs/design/design-tokens.json` 的 `breakpoint` / `spacing` / `layout` / `touch` 段），架构侧只讲工程怎么落地，不再定义第二套。Token 唯一准绳见 ADR-009。

#### 1. 事实基线（磁盘实测，不是估算）

| 项 | 实测结果 | 证据 |
|---|---|---|
| 响应式现状 | **零 `@media` 查询**。18 个 SFC 共 5172 行，无一处媒体查询 | `grep -l "@media" frontend/src/**/*.vue` 无结果 |
| viewport meta | 已有，写法正确 | `frontend/index.html:6` |
| 主题色漂移 | `theme-color` 与设计师主色不一致（Token 双源的第 3 个实例） | `frontend/index.html:8` vs `design-tokens.json` 的 `color.accent` |
| 表格组件 | **全项目零 `el-table`** —— "表格改卡片"这个问题**不存在** | 全量 grep 无 `el-table` |
| 后台页形态 | 已是卡片列表，非表格 | `AdminReview.vue` 的 `.task-card`、`AdminAudit.vue` 的 `.log-list` |
| 已有流体栅格 | `Circles.vue` 用 `repeat(auto-fill, minmax(260px, 1fr))`，**已流体，零改造** | `views/Circles.vue:162` |
| 主布局 | `el-aside width="220px"` 桌面外壳，**无任何移动端形态**（无 TabBar、无抽屉侧栏） | `layouts/MainLayout.vue:4` |
| 聊天页 | `el-aside width="280px"` 双栏主从 + `calc(100vh - 60px)` | `views/Chat.vue:5`、`:695` |
| 固定宽弹窗 | 3 处：380px / 480px / 420px | `Chat.vue:85`、`CircleDetail.vue:105`、`Circles.vue:48` |
| 日期选择器 | 1 处（2 个控件），在"发布活动"表单里 | `CircleDetail.vue:115`、`:118` |
| `100vh` | 4 处，移动端会被地址栏遮挡 | `Chat.vue:695`、`Login.vue:167`、`Register.vue:144`、`MainLayout.vue:139,142` |

#### 2. Element Plus 2.14.5 自带响应式能力盘点（先说清它能覆盖多少、落差在哪）

| 能力 | EP 是否提供 | 能否直接用 |
|---|---|---|
| 24 栅格 `el-row` / `el-col` | 是 | 可用，但**断点与设计师错位**（见下表） |
| 响应式属性 `:xs :sm :md :lg :xl` | 是 | **禁用** |
| `el-drawer`（含 `direction="btt"` 底部弹出） | 是 | **可用**，ActionSheet 就用它做 |
| `el-dialog` 的 `fullscreen` prop | 是 | **可用**，移动端弹窗用它 |
| `el-form` 的 `label-position="top"` | 是 | **可用**，移动端表单必须 top |
| `el-tabs` / `el-collapse` | 是 | 可用，需调密度 |
| 底部 TabBar | **否** | 落差 1 —— 需自研（约 80 行） |
| ActionSheet / BottomSheet | **否** | 落差 2 —— 用 `el-drawer` + 样式实现 |
| Safe Area（iPhone 底部安全区） | **否** | 落差 3 —— 需自研 CSS 工具类 |
| 移动端滚轮日期选择器 | **否** | 见下方组件裁决，判定为**不替换** |

**断点冲突（必须点名，这是最容易造出第二套体系的地方）**

| 档 | Element Plus 2.14.5 | 设计师（UIUX §6.1 / `design-tokens.json` 的 `breakpoint`） |
|---|---|---|
| xs | < 768 | 0（基础样式，即 < 640） |
| sm | ≥ 768 | ≥ 640 |
| md | ≥ 992 | ≥ 768 |
| lg | ≥ 1200 | ≥ 1024 |
| xl | ≥ 1920 | ≥ 1280 |
| 2xl | — | ≥ 1536 |

**完全错位**：EP 的 `sm=768` 是设计师的 `md`；EP 的 `md=992` 在设计师体系里**根本没有这一档**。

**硬规则**：禁用 `el-col` 的 `:xs` / `:sm` / `:md` / `:lg` / `:xl` 响应式属性。栅格一律用 CSS Grid + `@media (--ys-bp-*)`。用了 EP 的属性，就等于在设计师断点之外又埋了一套断点。

**断点落地方式（单一来源）**
- CSS 侧：加 `postcss-custom-media`（1 个 devDependency），在 `design-tokens.css` 顶部定义 6 条 `@custom-media --ys-bp-sm (min-width: 640px)` 等，业务代码写 `@media (--ys-bp-md)`。CSS 变量**不能**用在媒体查询里，所以必须走 custom-media 或写死 px，别无第三法。
- JS 侧：`src/composables/useBreakpoint.js` 用 `matchMedia` 读同一组数值，供 `isMobile` / `isTablet` / `isDesktop` 判断（弹窗全屏、抽屉方向等需要 JS 参与）。
- **同步纪律（诚实说明代价）**：断点值会同时存在于 `design-tokens.json`（源）、`design-tokens.css` 的 `@custom-media`、`useBreakpoint.js` 三处。改断点必须三处同改，`design-tokens.css` 顶部要写明这条纪律并标注源在 JSON。这是手工同步，有漂移风险 —— 引入生成脚本可以消除它，但对单人 MVP 项目不划算，故接受手工同步 + 文档纪律。

#### 3. 策略裁决：纯 CSS 断点改造 + 自研 3 个移动端组件，不引入 Vant

| 维度 | A 纯 CSS 断点改造 + 自研 3 组件 | B 引入 Vant 做移动端 | C 两套并存（EP 桌面 + Vant 移动，按 UA 分发） |
|---|---|---|---|
| 新增依赖 | 0 | +1（Vant 4.x，全量约 45KB gzip，按需约 15–25KB） | +1 |
| 视觉一致性 | 高（一套语言） | 中（Vant 自带圆角/字号/色板，需大量覆盖才能对齐 `--ys-*`） | **低 —— 两套组件库并存，是 P0「不得混用」在组件层的翻版** |
| 学习成本 | 0（用户已会 EP） | 中（新 API + 新主题定制方式） | 高（两套都要会 + 路由/UA 分发） |
| 移动端性能 | 好 | 好 | 差（两套都要下载或做动态分发） |
| 双端一致性维护 | 一份代码两档断点 | 两份代码 | 两份代码 |
| 与 Token 体系契合 | 高（直接用 `--ys-*`） | **低（Vant 用 `--van-*`，需再做一层 bridge）** | 低 |

**裁决：A。**

决定性理由：Vant 的 `--van-*` 变量体系与 `--ys-*` 是两套，要用它就得再写一层 bridge —— 这等于把刚在 ADR-009 里裁掉的"Token 双源"问题，在组件层原样重演一遍。而 EP 缺的只有 TabBar / ActionSheet / SafeArea 三样，自研总量约 200 行，比引入一整个库再改造它便宜得多，也不会引入第二套视觉语言。

#### 4. EP 组件的移动端替换形态裁决（不是等比缩放）

| 组件 | 现状 | 裁决 | 理由 |
|---|---|---|---|
| **表格** | 全项目零 `el-table` | **不需要处理** | 后台页已是卡片列表（`.task-card` / `.log-list`），移动端天然可用 |
| **弹窗**（3 处固定宽） | 380 / 480 / 420px | **不换组件，改形态**：`:fullscreen="isMobile"` 或 `width` 走 `useBreakpoint` 给 `92%` | 375px 视口下 380px 已溢出；且 UIUX §10.1 本就要求桌面居中 Modal、移动底部抽屉，两端形态不同 |
| **日期选择器**（1 处 2 个） | `CircleDetail` 发布活动表单 | **不换组件，只换容器**：放进 `el-drawer direction="btt"` 全宽底部弹出 | 这是低频后台操作（圈子主发活动），移动端滚轮选择器的收益不足以支撑换库 |
| **`el-menu` 侧栏** | 桌面 220px（`--ys-aside-w`） | 移动端**替换为自研 TabBar**（PRD 定的 5 入口：首页 / 圈子 / 发布 / 消息 / 我的），桌面保留 `el-menu` | `el-menu` 的垂直导航在 56px 顶栏下无处安放 |

结论：**不需要针对组件做"表格→卡片"式的替换，因为表格根本没用**。真正要做的是三件事：弹窗改全屏/底部、日期选择器改底部容器、侧栏换 TabBar。

#### 5. Top3 最难改的页面（逐文件取证）

| 排名 | 页面 | 行数 | 硬编码证据 | 为什么痛 |
|---|---|---|---|---|
| **1** | `views/Chat.vue` | 1072 | `:5` `<el-aside width="280px">`、`:695` `height: calc(100vh - 60px)`、`:85` `el-dialog width="380px"` | **双栏主从结构，移动端必须变单栏 + 路由切换**（列表页 ↔ 会话页）—— 这是**结构性改造，不是加 CSS**。`100vh` 在移动浏览器被地址栏遮挡，需改 `100dvh`。还要处理键盘弹起压缩话术区（UIUX §10.1） |
| **2** | `layouts/MainLayout.vue` | 229 | `:4` `<el-aside width="220px">`、`:139,142` `min-height: 100vh`、sticky `.topbar` 56px | **所有页面继承它**，改错一处全站受影响。要从零新增移动端 TabBar（56px，含安全区）+ 56px 顶栏 + 抽屉侧栏，同时保留桌面 220px 侧栏，两套外壳在同一组件里切换 |
| **3** | `views/CircleDetail.vue` | 372 | `:105` `el-dialog width="480px"`、`:115,118` 2 个 `el-date-picker`、`el-tabs` + `el-form` | 弹窗内表单是移动端重灾区：480px 弹窗在 375px 视口必溢出，且内部有 2 个日期选择器 + 多字段表单，需整体改为全屏/底部抽屉 + `label-position="top"` |

> 对照：`PostDetail.vue` 有 485 行（比 CircleDetail 长），但它是线性内容流，改造主要是间距、字号、图片响应式与触摸目标，**量反而低于 CircleDetail**。行数不等于难度，结构才决定难度。

#### 6. 工作量评估（19 个文件，给得出能决策的数字）

分档依据：**重** = 需结构性改造 / **中** = 需布局重组 / **轻** = 只需间距、字号、触摸目标。

| 文件 | 行数 | 档 | 人日 | 依据 |
|---|---|---|---|---|
| `layouts/MainLayout.vue` | 229 | 重 | 1.5 | 从零加移动端外壳；全站继承 |
| `views/Chat.vue` | 1072 | 重 | 2.5 | 双栏 → 单栏路由切换；100dvh；键盘 |
| `views/CircleDetail.vue` | 372 | 重 | 1.0 | 弹窗表单改底部抽屉 + 2 个日期选择器 |
| `components/PostCard.vue` | 176 | 中 | 0.5 | 被 Home / Profile / TopicDetail 复用，改一次收益三处 |
| `views/PostDetail.vue` | 485 | 中 | 0.5 | 长内容线性流 |
| `views/Profile.vue` | 379 | 中 | 0.5 | `el-row`/`el-col` 固定 span，改 CSS Grid 才好塌缩 |
| `views/Publish.vue` | 269 | 中 | 0.5 | 表单 + 媒体上传，移动端大按钮 |
| `views/Home.vue` | 218 | 中 | 0.5 | Feed 单列 + 加 UIUX §10.3 公告条 |
| `views/Notify.vue` | 235 | 中 | 0.5 | 加官方/互动 Tab 分组 + 置顶分组 |
| `views/Search.vue` | 143 | 中 | 0.3 | `el-col :span="14"` 移动端要满宽 |
| `views/AdminReview.vue` | 304 | 中 | 0.3 | 已是卡片列表，但审核操作区窄屏拥挤 |
| `views/AdminAudit.vue` | 225 | 中 | 0.3 | 同上 |
| `views/Login.vue` | 212 | 轻 | 0.25 | `100vh` → `100dvh`；居中卡片 |
| `views/Register.vue` | 195 | 轻 | 0.25 | 同上 |
| `views/Circles.vue` | 198 | 轻 | 0.25 | 已流体，只需把 `minmax` 从 260px 调到 160px + dialog 宽度 |
| `views/ActivityDetail.vue` | 160 | 轻 | 0.25 | 线性内容 |
| `views/TopicDetail.vue` | 135 | 轻 | 0.25 | 线性内容 |
| `views/Settings.vue` | 103 | 轻 | 0.25 | 表单 |
| `components/UserList.vue` | 59 | 轻 | 0.15 | 列表项 |
| `layouts/AuthLayout.vue` | 3 | 轻 | 0.05 | 空壳 |
| **页面小计** | 5172 | | **10.6** | 重 5.0（3 个）+ 中 3.9（9 个）+ 轻 1.7（8 个） |

一次性基建（不按页摊）：

| 项 | 人日 | 说明 |
|---|---|---|
| Token 接入 + EP Bridge（ADR-009） | 0.5 | 复制 `design-tokens.css`、改 `main.js` 引入顺序、删旧 `tokens.css` 的重定义 |
| 断点体系落地 | 0.5 | `postcss-custom-media` + 6 条 `@custom-media` + `useBreakpoint.js` + 禁用 EP 响应式属性 |
| 移动端基础组件 | 1.0 | TabBar / ActionSheet（基于 `el-drawer`）/ MobileDialog 包装 / SafeArea 工具类 |
| 全站触摸目标与间距审计 | 0.5 | 44×44 最小点击区 + 4px 网格，19 个文件过一遍 |
| 移动端真机联调 | 1.0 | iOS Safari + Android Chrome 各一轮，含 100dvh、键盘、安全区 |
| **基建小计** | | **3.5** |

**总计：10.6 + 3.5 = 14.1 人日（±20%，区间 11–17）**

三档决策数字（给用户拍板用，不写"视情况而定"）：

| 方案 | 人日 | 内容 | 代价 |
|---|---|---|---|
| **A 全量移动优先** | **14** | 上表全做，19 个文件全部移动优先 | 2–3 周纯前端工期 |
| **B 用户侧移动优先、后台页桌面** | **12.5** | A 减去 `AdminReview` / `AdminAudit` / `ActivityDetail` / `TopicDetail` / `Settings` / `UserList` 共 1.5 人日（这 6 个保持桌面，只做"不破版"兜底：不溢出、不横向滚动、安全区正确） | 管理员手机上审核体验差。**但只省 1.5 人日（11%），杠杆很小** |
| **C 只做不破版兜底** | **2** | 只做基建里的 Token 接入 0.5 + 断点体系 0.5 + 全站审计 0.5 + 半轮真机 0.5；19 个页面保持桌面布局，只保证窄屏不破版 | 手机用户看到的是挤在 375px 里的桌面布局。**省 12 人日（85%），但等于放弃 RICE 第一项** |

**我的建议：先回答"要不要真的移动优先"这个是非题，再谈档位。**

- 如果答案是**要**（大学生主力设备确实是手机，纯桌面版推广会劝退相当一部分人）→ **选 A（14）**。理由：B 只省 11%，而后台页本来就是最便宜的 6 个（合计 1.5 人日），为省这点钱留下一批"移动端没做完"的页面，反而让代码库出现两种完成度，不划算。要就做干净。
- 如果答案是**先不要**（想先用网页版验证需求真伪）→ **选 C（2）**，并明确写进路线图"移动端是 v1.1 的事"。**不要选 B** —— 它既没省下多少钱，又没有真正完成移动优先，是最容易两头不落好的一档。

B 唯一值得选的场景：能确认管理员**只会**在电脑上处理审核与举报（OD-19），且工期确实卡在 2 周内。

#### 7. 移动端性能预算（补充 §9）

| 指标 | 目标 | 说明 |
|---|---|---|
| 首屏 JS（gzip） | < 300 KB | |
| 首屏 CSS（gzip） | < 120 KB | |
| Web Font | **0 KB** | 设计师已定系统字体栈（UIUX §5.1） |
| LCP（校园网 4G） | **< 2.0 s** | 采用设计师 §13.5 的值，比 §9 原写的 2.5s 更严，§9 已同步 |
| `100vh` | 全部改 `100dvh` | 4 处，见事实基线表 |

#### 8. 移动端最大的隐藏阻塞：EP 全量引入架空了按需引入

**实测发现**：`main.js:3-6` 是 `app.use(ElementPlus)` + `import 'element-plus/dist/index.css'`（**全量**），而 `vite.config.js:20-31` 同时配置了 `unplugin-vue-components` + `ElementPlusResolver`（**按需**），并且 `vite.config.js:32-35` 的 `manualChunks: { 'element-plus': ['element-plus'] }` 把整个 EP 强制打成一个 chunk。

**后果**：按需引入完全失效，等于全量打包。EP 全量 CSS 约 320KB min / 60KB gzip，JS 约 900KB min / 280KB gzip —— 这直接击穿上面的移动端首屏预算，也让 §9 "首包 JS < 500KB gzip" 不可能达标。

**修法（0.3 人日）**：删掉 `main.js` 里的 `app.use(ElementPlus)` 与 `element-plus/dist/index.css` 两行，让 unplugin 接管按需引入（组件已在模板里用着，去掉全量两行即可）；`manualChunks` 去掉 `element-plus` 项，改由 Rollup 自动分包。

登记为 **TD-21（D1）**。**这条不修，移动端做得再漂亮也快不起来。**

→ 详见 `decisions/ADR-009.md`（Token 唯一准绳与接入方式）

### Q9 F2 校内身份归属与认证（Phase 1 遗漏，本轮补齐）

> Phase 1 只答辩了 G1/G2/G3 三个缺口，漏掉了 MVP 里的 F2 与 F11。本节与 Q10 按 Q6 同样粒度补齐。

#### 9.1 数据落地：`user` 表加列，不新建表

| 列 | 类型 | 默认 | 来源 |
|---|---|---|---|
| `school_id` | BIGINT | 1 | SPEC §6.2 |
| `verify_status` | TINYINT | 0 | SPEC §6.2（0未认证 1已认证 2审核中 3驳回） |
| `stranger_msg_enabled` | TINYINT | 1 | SPEC §6.2 |
| `onboarding_step` | TINYINT | 0 | **增补申请**，理由见 Q10.1 |
| `name_tail` | VARCHAR(8) | NULL | **增补申请**，理由见 9.4 |

DDL 见 `docs/sql/migration_v1.sql` 第 4 节。**不建 `school` 表**：MVP 单校只有一行数据，学校名由 `application.yml` 的 `campus.school-name` 提供，为一个常量建表是过度设计。

#### 9.2 校园邮箱认证：白名单 + 复用现有通道，不新开辟

**域名白名单怎么配**：`application.yml` 增加 `campus.email-domain-whitelist`，逗号分隔（如 `mail.xxx.edu.cn,xxx.edu.cn`），启动时读入一个 `Set<String>`。**不进数据库**——白名单是部署级配置，进库要额外做缓存与刷新，且改一次要发版一次的频率远低于收益。

**校验顺序不可调换**：先判白名单，**再**写 Redis、**再**调 SMTP。这是 `VerifyCodeServiceImpl.java:55-58` 已经确立的纪律（非法格式必须在写 Redis 与调用发信通道之前拦截），否则任意字符串会成为 Redis key 并被送去真实 SMTP 通道。

**通道复用裁决：完整复用 `VerifyCodeServiceImpl.send(target, "campus")`，不新开辟。** 理由：6 位码、5 分钟 TTL、60s 限频（`code:limit:*`）、每日 10 次上限（`code:daily:*`）、失败清理已生成的码并释放限频锁——这五条全是现成的且已跑通。新开辟等于复制一份，并且要重新踩一遍 Redis 5.0 的坑。多出来的只有一个 `type` 分桶参数，零成本。

#### 9.3 Redis 5.0 约束与一处 Phase 1 自相矛盾的修正

原子读删一律走 Lua（`ARCHITECTURE.md` §6.5 脚本 1），**禁止** `opsForValue().getAndDelete()`——它底层发 GETDEL，Redis 5.0 抛 `ERR unknown command`，本项目已踩过一次（SPEC §11 第 1 条）。

**必须同时修正的自相矛盾**：§6.5 写「Lua 脚本新建 `util/RedisLua.java` 集中管理，禁止散落」，但磁盘实测 `util/RedisLua.java` **并不存在**，而 `GET_AND_DELETE` 脚本当前定义在 `VerifyCodeServiceImpl.java:102-105`。即 Phase 1 定的纪律与代码现状不一致，且本轮 F2（验证码）、F3（ZSET 滑动窗口配额）还要再写 2 处 Lua。

**裁决**：Phase 3 第一个后端 PR 把 `GET_AND_DELETE` 抽到 `util/RedisLua.java`，并新增「滑动窗口限频」脚本（§6.5 脚本 4），`VerifyCodeServiceImpl` 改为引用。**纯搬迁，不改语义、不改行为**，0.1 人日。不做这一步，§6.5 的「禁止散落」就是一句空话，且本轮会新增第 3 处散落点。

#### 9.4 学号 + 姓名后两位：冷启动人工核验兜底 —— **「进 review_task」不可实现，必须改判**

**SPEC §5.4 写的「→ 进 review_task」在现有 schema 下实现不了。** 实测证据：

- `sql/schema.sql:303-315`：`review_task` 的 `post_id BIGINT NOT NULL`，且 `UNIQUE KEY uk_post(post_id)`
- `entity/ReviewTask.java:17`：只有 `postId` 字段，**没有** `targetType` / `targetId`
- `service/ReviewService.java:16-27`：`approve()` 的语义是「post.status 0→1 写扩散」，`reject()` 是「post.status 0→2」

即 `review_task` 是**帖子专用**队列，塞不进一条「用户实名核验」任务。

**同一个错误在 Phase 1 我自己写的文字里还有两处**，一并纠正：

| 位置 | 原表述 | 问题 | 改判 |
|---|---|---|---|
| `ARCHITECTURE.md:276`（Q6 G1） | 「举报进入现有 `review_task` 流」 | 同上，举报的 `target_type` 有 5 种，多数不是帖子 | **举报不进 `review_task`，在新建的 `report` 表闭环**。`report.status` 就是 AC-14 要求的「处理状态回执」，`GET /admin/reports` + `POST /admin/reports/{id}/handle` 已经构成完整处置流 |
| SPEC §9 AC-06 | 「搭话内容触发敏感词 → 写入 `review_task`」 | 搭话是聊天消息，没有 `post_id` | **改写 `audit_log`**。`audit_log` 本身就是多态的（`schema.sql:325-327` 的 `target_type` / `target_id`）且只增不删，是现成的留痕表。`action = 'chat_block_sensitive'`、`target_type = 'chat_message'`、`detail` 存命中词 JSON |

**学号核验的最终裁决（不新增表、不改 `review_task`）**：

1. 提交时 `user.verify_status` 置 2（审核中），`student_no` 已有 UNIQUE 兜底，`name_tail` 存姓名后两位供管理员比对
2. **队列就是 `user` 表本身**：管理员端 `SELECT * FROM user WHERE verify_status = 2 ORDER BY update_time`。MVP 用户量在百级，**不建索引**（§10「避免过早优化」），全表扫几百行的成本低于维护一个索引
3. 处置就是 `UPDATE user SET verify_status = 1 或 3`，同时写 `audit_log`（`action = 'verify_approve' / 'verify_reject'`，`target_type = 'user'`）
4. 管理员入口挂进现有 `AdminReview.vue` 的一个新分组（OD-19 已裁定该页必须手机端可用）

**为什么这样比「改造 review_task 成多态」好**：后者要 `MODIFY post_id BIGINT NULL` + 加 `target_type` + 改唯一键 + 改 `ReviewService` 全部方法语义，侵入一条已跑通的审核链路，约 0.5–1 人日且回归风险落在 MVP 的放行项上。前者零改动现有表。

**防滥用的边界（诚实说）**：邮箱路径可能出现「一个校园邮箱认证多个账号」。主防线是学号路径的 `student_no` UNIQUE；邮箱路径用 Redis 键 `campus:email:{email}` 做 `setIfAbsent` 幂等绑定。Redis 无持久化保证，最坏情况是最多重复绑定几次，风险可接受（校园邮箱本身有获取门槛）。若上线后观测到真实滥用，再补 `user.campus_email` 列 + 唯一索引，0.2 人日。

#### 9.5 未认证限制：注解 + 拦截器统一收口，不散落在 Controller

**现状**：权限判断是散落的——`ReviewController.java:43,53,60,70` 每个方法第一行各写一次 `roleService.requireAdmin(me)`。加 F2 后若照此写法，`/chat/send`、`/admin/announcement` 会各写一次 `verify_status` 判断，以后每加一个受限端点就多一处，漏一处就是一个越权口子。

**裁决：新增 `@RequireVerified` 方法注解 + `VerifiedInterceptor`。**

```java
// 拦截器核心（顺序必须排在 AuthInterceptor 之后，因为要读 ATTR_USER_ID）
if (!(handler instanceof HandlerMethod hm)) return true;
if (!hm.hasMethodAnnotation(RequireVerified.class)) return true;
Long me = (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
if (me == null) return true;                       // 401 由 AuthInterceptor 已短路
User u = userMapper.selectById(me);                // 主键查询
if (u == null || !Integer.valueOf(1).equals(u.getVerifyStatus())) {
    write403(response, ResultCode.USER_NOT_VERIFIED);   // 403
    return false;
}
return true;
```

**为什么用拦截器而不是 AOP 切面**：
1. 拦截器能直接写 HTTP 403 并短路；AOP 抛异常后还要 `GlobalExceptionHandler` 转一次，多一跳
2. `HandlerInterceptor.preHandle` 的 `handler` 参数直接是 `HandlerMethod`，取注解零成本；AOP 要维护切点表达式的包名字符串，重构时易失效
3. 与现有 `AuthInterceptor`（`WebMvcConfig.java:33-48`）同构，团队已有心智模型
4. 当前 `pom.xml` 没有 `spring-boot-starter-aop`，不引入新依赖

**为什么注解驱动而不是路径白名单表**：路径表要在「配置处」和「Controller 处」两处维护，加端点时漏登记是静默失败；注解跟着方法走，漏了就是看得见的遗漏。

**其他三条口径**：
- **不缓存 `verify_status`**：`user` 表单主键查询在 MVP 量级是微秒级，加缓存要处理失效，属过度设计
- **管理员不豁免**：AC-11 未区分角色。统一规则，避免「管理员可绕过认证」的口子
- **前端不重复判断**：`GET /user/verify/status` 返回 `canStrangerChat` / `canPublishAnnouncement`，前端据此禁用入口；后端拦截器是兜底，两层都要有

#### 9.6 配套：错误码与 HTTP 状态码

本轮需给 `common/ResultCode.java` 增 5 项（`TOO_MANY_REQUESTS(429)`、`USER_NOT_VERIFIED(1029)`、`STRANGER_MSG_CLOSED(1030)`、`ANNOUNCEMENT_NOT_FOUND(1031)`、`CAMPUS_EMAIL_NOT_ALLOWED(1032)`），并给 `GlobalExceptionHandler` 加一张约 12 项的「业务码 → HTTP 状态码」映射表。

**为什么必须改后端**：现有 `GlobalExceptionHandler.business()`（`GlobalExceptionHandler.java:30-34`）对业务异常一律返回 **HTTP 200**，错误只在响应体 `code` 里。而 SPEC §9 AC-04 要求 429、§12 步骤 3.4 要求 403。二者冲突。

**为什么只映射 12 项而不是全部**：其余 1xxx 业务码保持 HTTP 200 不变，零回归风险。前端 `src/utils/request.js:41-49` 的成功分支判 `resp.data.code === 200`，错误分支读 `error.response.data.message` 弹 toast——两条路都能拿到文案，切换后不丢提示。完整映射表见 `docs/api/openapi.yaml` 的 `info.description` 第 4 节。

---

### Q10 F11 新用户引导与种子内容（Phase 1 遗漏，本轮补齐）

#### 10.1 引导状态持久化：加 `user.onboarding_step`，**不新增表**

**裁决：加一个 `TINYINT` 列，不用现有字段推导。**

反对「推导」的三条理由：

1. **幂等判据不成立**。「第 3 步一个都没选就点了完成」的用户，与「填了归属但还没做引导」的用户，在现有字段上完全同构（`dept`/`grade` 非空、`user_tag` 0 行、`relation` 0 行），无法区分。没有显式标记，`POST /onboarding/complete` 的重复提交无法判定为「已完成」
2. **「主动跳过」这个语义推导不出来**。跳过与未做在数据上同构
3. **推导式判据会漂移**。今天用「有标签」代表第 2 步完成，明天若允许 0 个标签，判据就失效了。显式列是稳定的

代价：1 个 TINYINT（1 字节），不新增表、不需要 join、不需要迁移历史数据。

**四档语义**：`0` 未开始 / `1` 已填归属 / `2` 已选标签 / `3` 已完成。

**AC-18 的阻断判据不依赖这一列**：第 1 步直接用 `dept IS NULL OR grade IS NULL` 判定，前端路由守卫与后端 `/onboarding/complete` 入参校验**两端都判**，防止前端被绕过。

#### 10.2 `onboarding/complete` 的幂等：两道防线，不需要分布式锁

**第 1 道（快速路径）**：进入时读 `onboarding_step == 3` → 直接返回 200 + `duplicated = true`，不写任何数据。

**第 2 道（并发路径）**：用单条 UPDATE 的行锁串行化，不用 Redis 锁：

```sql
UPDATE user SET dept = ?, grade = ?, school_id = ?, onboarding_step = 3
WHERE id = ? AND onboarding_step <> 3
```

影响行数 = 0 说明已被并发请求或历史请求完成 → 返回 `duplicated = true`。InnoDB 下同一行的 UPDATE 天然互斥，这条语句本身就是幂等键。

**其余写入的幂等**：
- 标签：覆盖语义，先 `UPDATE user_tag SET is_deleted = 1 WHERE user_id = ?` 再 `INSERT IGNORE`，`uk_user_tag` 兜底
- 关注：复用现有 `RelationService`，已关注则跳过不报错
- 事务边界：一个 `@Transactional` 包住上述写库动作；Redis 与 WS 推送不放进事务

#### 10.3 推荐关注的人：确定性规则排序，冷启动不上算法

PRD §9 已把「AI 智能匹配 / 推荐算法」列为明确不做。排序规则按优先级从高到低：**同院系 > 同年级 > 已认证 > 曾有互动**。用一条 SQL 的可解释打分表达，不做召回、不做向量：

```sql
SELECT u.id, u.nickname, u.avatar_url, u.dept, u.grade, u.verify_status, u.follower_count,
       (CASE WHEN u.dept  = ? AND u.dept  IS NOT NULL THEN 4 ELSE 0 END) +
       (CASE WHEN u.grade = ? AND u.grade IS NOT NULL THEN 2 ELSE 0 END) +
       (CASE WHEN u.verify_status = 1 THEN 1 ELSE 0 END) AS score
FROM user u
WHERE u.id <> ? AND u.status = 0 AND u.is_deleted = 0 AND u.role = 0 AND u.school_id = ?
  AND NOT EXISTS (SELECT 1 FROM relation r
                  WHERE r.user_id = ? AND r.target_id = u.id AND r.type IN (1, 3))
ORDER BY score DESC, u.follower_count DESC, u.id DESC
LIMIT ?
```

- 权重 `同院系 4 > 同年级 2 > 已认证 1`，末位 `u.id DESC` 保证同分结果稳定、翻页不抖动
- **「曾有互动」这一档冷启动期没有数据**，降级用 `follower_count` 代理。等 DAU 破 500 再开第五档 `EXISTS (近 7 天 post / comment)`
- 排除自己、已关注、已拉黑（`relation.type = 3`）、非正常状态、管理员账号
- `reason` 字段把命中了哪条规则直接下发给前端展示（「同院系 · 计算机科学与技术学院」）——规则可解释，这是不上算法换来的最大好处

#### 10.4 推荐圈子：同样是规则，且允许返回空

```sql
SELECT c.*, (SELECT COUNT(*) FROM circle_member cm WHERE cm.circle_id = c.id) AS member_count
FROM circle c
WHERE c.is_deleted = 0
  AND NOT EXISTS (SELECT 1 FROM circle_member m WHERE m.circle_id = c.id AND m.user_id = ?)
ORDER BY member_count DESC, c.create_time DESC
LIMIT ?
```

冷启动期圈子可能不足，**允许返回少于 `size` 条甚至空数组**。空态不白屏：`emptyHint` 由服务端下发（已在 `openapi.yaml` 的 `OnboardingRecommendCirclesResponse` 定义），前端直接渲染文案 + 行动按钮，满足 AC-19 且保证前后端文案一致。

#### 10.5 种子内容（≥50 条真实内容）—— 依赖 PM，不用生成器

`sql/seed_data.sql` 现有 5 个账号与若干帖子（实测值行 21 条），距 AC「上线前 ≥50 条真实种子内容」有缺口。

**裁决**：由 PM 提供真实校园内容，走 `seed_data.sql` 扩写，**不用随机生成器**。理由有二：生成器产出的是假内容，违反 AC-19 的「真实引导」要求；也违反 P0 规则「禁止空洞占位文案」——假内容和占位文案在用户眼里是同一件事。登记为 Phase 3 依赖 PM 的输入项。

---

## 4. 选型对比矩阵汇总

| 决策点 | 方案 A | 方案 B | 方案 C | 裁决 |
|---|---|---|---|---|
| 改造方式 | 增量复用 | 推倒重写 | — | **A** |
| 部署 | 云服务器单机直装 | 服务器 Docker | 托管 Serverless | **A** |
| 验证码通道 | 邮箱 SMTP | 阿里云号码认证-短信认证 | 腾讯云/阿里云传统短信（需企业资质） | **A（v1.1 增补 B）** |
| 支付 | 不做 | 微信支付（小微） | 微信支付（个体/企业） | **A** |
| WS 广播 | 内存广播 | Redis PubSub | MQ（RabbitMQ/RocketMQ） | **A（临界点后转 B，永不选 C）** |
| 图标库 | @element-plus/icons-vue | lucide-vue-next | Iconify | **A** |
| 生产数据库 | MySQL 8.4 LTS | MySQL 9.0（现状本地） | MySQL 9.7 LTS | **A** |
| 生产 Redis | Redis 7.x（代码按 5.0 语义） | Redis 5.0（与本地一致） | — | **A** |
| 移动端响应式 | 纯 CSS 断点改造 + 自研 3 组件 | 引入 Vant | EP 桌面 + Vant 移动双库并存 | **A**（Q8） |
| 断点体系 | 设计师断点（640/768/1024/1280/1536） | Element Plus 内置（768/992/1200/1920） | — | **A**，禁用 EP 响应式属性 |
| 设计令牌 | `design/design-tokens.css` 唯一准绳 | `frontend/src/styles/tokens.css` 继续用 | 两者并存 | **A**（ADR-009） |

---

## 5. 分层架构

```
┌─────────────────────────────────────────────────────────────────┐
│  客户端层  Vue 3.5 SPA（Vite 构建，Nginx 托管静态产物）             │
│  views(16) / components / layouts / stores(Pinia) / api / utils  │
│  图标: @element-plus/icons-vue 2.3.2 · 尺寸 16|20|24              │
└───────────────────────────┬─────────────────────────────────────┘
                            │ HTTPS  /api/v1/*  REST
                            │ WSS    /ws/chat  /ws/notify
┌───────────────────────────▼─────────────────────────────────────┐
│  接入层  Nginx 1.24+                                              │
│  - 静态资源（/assets 长缓存，gzip）                                │
│  - /api/v1/* → rewrite → 后端 127.0.0.1:8080                      │
│  - /ws/*     → Upgrade 反代（proxy_read_timeout 300s）             │
│  - /uploads/ → 反代后端                                            │
│  - 80 → 443 强制跳转；TLS 1.2+                                     │
└───────────────────────────┬─────────────────────────────────────┘
                            │
┌───────────────────────────▼─────────────────────────────────────┐
│  表现层  controller/（12 → 15）                                    │
│  职责：参数校验 + 调 service + 组装 VO。禁止写业务逻辑              │
│  横切：AuthInterceptor(JWT) / GlobalExceptionHandler / Result包装  │
├─────────────────────────────────────────────────────────────────┤
│  业务层  service/ + service.impl/                                  │
│  @Transactional 落在这里；计数一律事务内 SET x = x ± 1              │
│  新增域：ReportService / BuddyService / AnnouncementService        │
│  复用域：ChatService / NotifyService / ReviewService / RoleService  │
├─────────────────────────────────────────────────────────────────┤
│  数据层  mapper/(MyBatis-Plus BaseMapper) + entity/                │
│  22 表 → 29 表（+report, interest_tag, user_tag,                   │
│                 buddy_post, buddy_join, announcement,              │
│                 announcement_read）                                │
├─────────────────────────────────────────────────────────────────┤
│  基础设施                                                           │
│  MySQL 8.4 LTS(utf8mb4)  Redis 7(缓存/验证码/限频/ZSET窗口)         │
│  WsSessionManager(内存)  SensitiveWordFilter(DFA)                   │
│  SmtpMessageGateway  UploadService(本地磁盘)                        │
│  task/(敏感词刷新、活动状态机)                                       │
└─────────────────────────────────────────────────────────────────┘
```

### 5.1 目录与文件组织硬约束

沿用现有分层，新增代码必须落在既有目录，禁止新建包：

| 目录 | 约束 |
|---|---|
| `controller/` | 每方法 ≤ 30 行；不出现 `Mapper` 注入；不出现 `@Transactional` |
| `service/impl/` | 单文件 ≤ 300 行；一个文件只服务一个域；超限按子域拆 |
| `mapper/` | 只放 SQL（注解或 XML）；不放业务判断 |
| `entity/` | 与表 1:1；逻辑删除字段统一 `isDeleted` |
| `dto/` `vo/` | 入参出参分离，Controller 不直接收 entity |
| `common/` | `Result` / `ResultCode` / `CursorPage` 全局复用，禁止各域自建返回包装 |
| `exception/` | 业务异常统一 `BusinessException` + `ResultCode`，禁止 `RuntimeException` 裸抛 |
| `config/` | 只放装配，不放业务逻辑 |
| `websocket/` | 推送入口收敛在 `WsPushService`，业务层禁止直接碰 `WebSocketSession` |

### 5.2 依赖方向（单向，不可逆）

```
controller  →  service  →  mapper  →  entity
     ↓            ↓
   vo/dto      common/exception
```

禁止：mapper 反向调 service；controller 直接注入 mapper；entity 出现在 Controller 的方法签名里（新增代码）。

---

## 6. 版本锚定表

> 原则：**先确认实际安装版本，再按该版本 API 写代码**。下表 "实测" 列来自磁盘/package-lock 实际解析结果，非 `package.json` 声明。

### 6.1 本地开发环境（现状实测）

| 组件 | 实测版本 | 处置 |
|---|---|---|
| OS | Windows 10 专业版 21H1 (build 19043)，非正版不可升级 | 锁死，不折腾 |
| JDK | OpenJDK 17.0.19 (Microsoft) LTS | 锁 17 |
| Maven | Apache Maven 3.9.15 | 锁 |
| Node | v22.22.2 | 锁，服务器构建用同大版本 |
| npm | 10.9.7 | — |
| MySQL Server | 9.0（**Innovation，EOL 2024-10-15**） | **加装 8.4 LTS 实例（端口 3307）作为 dev 主库**，见 ADR-005 |
| Redis | 5.0.14.1 (Windows) | 保留不动；代码按 5.0 语义写 |

### 6.2 后端依赖（锁定，不改）

| 依赖 | 版本 | 说明 |
|---|---|---|
| Spring Boot (parent) | **3.2.5** | 不动 |
| Java | **17** | 不动（不升 21） |
| mybatis-plus-spring-boot3-starter | **3.5.7** | 不动 |
| mysql-connector-j | **8.4.0** | **用户明确要求保持，勿升 9.x**。且与生产 MySQL 8.4 LTS 主版本对齐 |
| jjwt (api/impl/jackson) | **0.12.5** | 不动 |
| hutool-all | **5.8.27** | 不动 |
| spring-boot-starter-mail | 由 parent 管理 | 不动 |
| spring-boot-starter-websocket | 由 parent 管理 | 不动 |
| spring-boot-starter-data-redis | 由 parent 管理（Spring Data Redis 3.2.x / Lettuce 6.3.x） | 不动。**上线前用 `mvn dependency:tree` 确认实际 Lettuce 版本并回填此表** |
| springdoc-openapi-starter-webmvc-ui | **2.5.0** | 不动 |
| spring-boot-starter-actuator | **新增**，版本随 parent | TD-09 修复项 |
| spring-boot-starter-validation | 由 parent 管理 | 已存在 |

### 6.3 前端依赖（锁定）

| 依赖 | package.json 声明 | lock 实际解析 | 处置 |
|---|---|---|---|
| vue | ^3.4.0 | **3.5.42** | 保持 caret |
| vite | ^5.4.0 | **5.4.21** | 保持 caret |
| element-plus | ^2.8.0 | **2.14.5** | **改为精确 `2.14.5`**（caret 会静默升小版本，EP 小版本有 UI 回归史）→ TD-12 |
| @element-plus/icons-vue | **未声明（仅传递依赖）** | **2.3.2** | **提升为直接依赖，锁 `2.3.2`** → ADR-003 |
| pinia | ^2.2.0 | **2.3.1** | 保持 caret |
| vue-router | ^4.4.0 | **4.6.4** | 保持 caret |
| axios | ^1.7.0 | **1.20.0** | 保持 caret |
| dayjs | ^1.11.10 | **1.11.23** | 保持 caret |
| @vitejs/plugin-vue | ^5.0.0 | **5.2.4** | 保持 caret |
| unplugin-auto-import | ^0.18.0 | **0.18.6** | 保持 caret |
| unplugin-vue-components | ^0.27.0 | **0.27.5** | 保持 caret |

### 6.4 生产环境（目标版本）

| 组件 | 版本 | 说明 |
|---|---|---|
| OS | Ubuntu 22.04 LTS | 教程与社区资料最多，出问题好查 |
| JDK | Eclipse Temurin 17.0.x LTS | 与本地同为 17 |
| Nginx | 1.24 或 1.26（用 nginx 官方源，不用 Ubuntu 源的 1.18） | — |
| MySQL Server | **8.4.x LTS** | Premier 至 2029-04-30，Extended 至 2032-04-30 |
| Redis | **7.2.x** | 生产跑 7，代码按 5.0 语义写 → 两边都安全 |
| Node（仅构建期） | 20 LTS 或 22 LTS | 与本地 22 同大版本 |

### 6.5 Redis 5.0 兼容性硬约束（**必读，已踩过一次坑**）

**规则：所有 Redis 代码必须能在 Redis 5.0.14.1 上跑通。生产用 7.x 是向下兼容的选择，不是放开口子。**

**禁用指令清单（6.0 / 6.2 / 7.0 新增，5.0 全部不支持）**

| 指令 | 最低版本 | 禁用原因 |
|---|---|---|
| `GETDEL` | 6.2 | **已踩坑**：`opsForValue().getAndDelete()` 底层发 GETDEL，Redis 5.0 抛 `ERR unknown command` |
| `GETEX` | 6.2 | `opsForValue().getAndExpire()` 底层 |
| `GETEX`/`SET ... EXAT` / `PXAT` | 6.2 | 绝对过期时间戳 |
| `SET ... KEEPTTL` | 6.0 | 覆盖写保留 TTL |
| `SET ... GET` | 6.2 | 返回旧值 |
| `LPOS` | 6.0 | `opsForList().indexOf()` 可能用到 |
| `ZRANDMEMBER` / `HRANDFIELD` | 6.2 | `opsForZSet().randomMember()` |
| `SMISMEMBER` | 6.2 | 批量判存 |
| `ZRANGESTORE` | 6.2 | 有序集结果存新键 |
| `COPY` | 6.2 | `RedisTemplate.copy()` |
| `SINTERCARD` | 7.0 | 交集计数 |
| `EXPIRE` 的 `NX`/`XX`/`GT`/`LT` 选项 | 7.0 | `expire(key, ttl, ExpiryPolicy)` |
| `STRALGO LCS` | 6.0 | — |
| ACL 系列 / RESP3 / client-side caching | 6.0 | — |

**Spring Data Redis API 禁用清单**

```java
// 禁用（以下在 Redis 5.0 上必抛异常）
redisTemplate.opsForValue().getAndDelete(key);            // GETDEL 6.2
redisTemplate.opsForValue().getAndExpire(key, ttl);       // GETEX 6.2
redisTemplate.opsForZSet().randomMember(key);             // ZRANDMEMBER 6.2
redisTemplate.opsForZSet().distinctRandomMembers(k, n);   // ZRANDMEMBER 6.2
redisTemplate.copy(src, dst, true);                       // COPY 6.2
redisTemplate.expire(key, ttl, ExpiryPolicy.NX);          // EXPIRE NX 7.0

// 安全
redisTemplate.opsForValue().get / set / delete / increment
redisTemplate.opsForValue().setIfAbsent(key, val, Duration)  // 底层 SET NX PX，5.0 支持
redisTemplate.expire(key, ttl)                            // 无选项，安全
redisTemplate.execute(RedisScript, keys, args)             // Lua，原子，5.0 支持
```

**Lua 替代写法清单（新建 `util/RedisLua.java` 集中管理，禁止散落）**

```lua
-- 1) GETDEL 替代：原子取出并删除（已在 VerifyCodeServiceImpl.verify 使用）
local v = redis.call('GET', KEYS[1])
if v then redis.call('DEL', KEYS[1]) return v end
return ''

-- 2) GETEX 替代：取出并续期
local v = redis.call('GET', KEYS[1])
if v then redis.call('EXPIRE', KEYS[1], ARGV[1]) end
return v

-- 3) SET KEEPTTL 替代：覆盖写但保留原 TTL
local ttl = redis.call('PTTL', KEYS[1])
redis.call('SET', KEYS[1], ARGV[1])
if ttl > 0 then redis.call('PEXPIRE', KEYS[1], ttl) end
return 1

-- 4) 滑动窗口限频（G1 陌生人私信配额用这个）
-- KEYS[1]=窗口键  ARGV[1]=窗口左边界毫秒  ARGV[2]=上限  ARGV[3]=当前毫秒  ARGV[4]=成员  ARGV[5]=窗口毫秒
redis.call('ZREMRANGEBYSCORE', KEYS[1], 0, ARGV[1])
local n = redis.call('ZCARD', KEYS[1])
if n < tonumber(ARGV[2]) then
  redis.call('ZADD', KEYS[1], ARGV[3], ARGV[4])
  redis.call('PEXPIRE', KEYS[1], ARGV[5])
  return 1
end
return 0

-- 5) 分布式锁释放（check-and-del，防止误删他人锁）
if redis.call('GET', KEYS[1]) == ARGV[1] then
  return redis.call('DEL', KEYS[1])
end
return 0
```

**纪律**：新增任何 Redis 调用前，先问"Redis 5.0 有这条指令吗"。不确定就走 Lua。

### 6.6 MySQL 版本约束

| 项 | 结论 |
|---|---|
| 生产版本 | **MySQL 8.4.x LTS**（Premier 2029-04-30 / Extended 2032-04-30） |
| 本地现状 | 9.0（Innovation，**EOL 2024-10-15**）—— 不再收到任何安全补丁 |
| 迁移路径 | **9.0 → 8.4 是降级。MySQL 官方明确：Innovation 版本降级需 `mysqldump` 逻辑导出 + 导入，不支持原地降级** |
| 建议动作 | 在本地加装 MySQL 8.4 LTS 实例（端口 3307，服务名 MySQL84），dev 直接连 8.4。避免"本地 9.0 跑通、生产 8.4 报错" |
| 连接器 | `mysql-connector-j` 保持 **8.4.0**（用户明确要求），与服务端 8.4 主版本对齐 |
| 字符集 | `utf8mb4` / `utf8mb4_unicode_ci`（`docker-compose.yml:26-27` 已配，服务器直装需手工配） |

---

## 7. 数据模型增量

新增 7 张表（22 → 29），ER 关系：

> **勘误**：本行原文写「23 → 30」，§5 分层架构图原文写「23 表 → 29 表」，两处自相矛盾且基数均错。
> 实测 `ls backend/src/main/java/com/campus/social/entity/*.java` = **22**，故终态为 **29**。以 SPEC §2.3 为准。

```
user ──┬──< user_tag >── interest_tag
       │
       ├──< buddy_post >── buddy_join >── user
       │         └── tag_id → interest_tag
       │
       ├──< report >(target_type/target_id 多态指向 user/post/comment/chat_message/buddy_post)
       │
       └──< announcement_read >── announcement
                                        └── publisher_id → user

chat_conversation (已存在，不改动结构)
  └── 新增护栏：发送前查 relation(type=3 黑名单) + Redis 配额窗口
```

**通用字段约定（所有新表必带）**：
`id BIGINT AUTO_INCREMENT`、`create_time DATETIME DEFAULT CURRENT_TIMESTAMP`、`update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP`、`is_deleted TINYINT DEFAULT 0`

**逻辑删除**：MyBatis-Plus 全局配置已生效（`application.yml:60-64`，字段 `isDeleted`），新表沿用即可，无需额外配置。

---

## 8. 安全 Checklist

> 上线前逐项打钩，一项不过不放行。

**鉴权与授权**
- [ ] 所有 `/api/v1/admin/**` 端点首行调 `roleService.requireAdmin(uid)`（G3 的 4 个 admin 端点）
- [ ] JWT `access-secret` / `refresh-secret` 各 ≥64 字节强随机（`openssl rand -base64 64`），**两者不同**
- [ ] refresh token jti 存 Redis，刷新即删旧，重用即 401（已实现，回归验证一次）
- [ ] access token TTL ≤ 15min，refresh ≤ 7d（已配 `application.yml:73-74`）
- [ ] 水平越权：所有"查/改我的资源"接口以 token 里的 uid 为准，**不信任请求参数里的 userId**
- [ ] 垂直越权：非管理员访问 admin 端点返 403（`NOT_ADMIN`）

**网络安全**
- [ ] CORS `allowedOriginPatterns` 收窄为实际域名（**TD-01，当前是 `*`**）
- [ ] WebSocket `setAllowedOrigins` 收窄为实际域名（**TD-02，当前是 `*`**）
- [ ] 云安全组只放行 **22 / 80 / 443**；3306 / 6379 / 8080 **绝不对公网开放**
- [ ] Nginx 80 → 443 强制跳转；TLS 1.2+，禁用 TLS 1.0/1.1
- [ ] MySQL / Redis 只监听 127.0.0.1
- [ ] SSH 禁密码登录，改用密钥；改默认端口（可选）

**输入与输出**
- [ ] SQL：全部 MyBatis-Plus `#{}`，无字符串拼接（已实现，新增代码同样遵守）
- [ ] XSS：前端禁止对用户内容用 `v-html`；必须用时先做白名单清洗
- [ ] 敏感词：所有 UGC 入口（发帖/评论/私信/搭子/公告）过 `SensitiveWordFilter`
- [ ] 文件上传：扩展名白名单 + MIME 校验 + 大小限制 + **服务端重命名**（禁止用原始文件名拼路径，防 `../` 穿越）
- [ ] 限频：短信/邮箱验证码（已有）、陌生人私信（G1 新增）、举报提交、搭子发布

**数据与合规**
- [ ] 密码 BCrypt 存储，不明文、不 MD5（回归验证一次）
- [ ] 日志脱敏：手机号、邮箱、token 不落日志
- [ ] `uploads` 目录不对公网列目录
- [ ] 每日 `mysqldump` 全量备份 + 异地保存（单机部署的命根子）
- [ ] 用户注销：数据保留但去标识化（`status=2` 语义已有）

**运维**
- [ ] `/actuator/health` 只对内网开放（**TD-09，当前无 actuator**）
- [ ] 应用以非 root 用户运行
- [ ] JVM 参数固定：`-Xms768m -Xmx1280m -XX:+UseG1GC`（2核4G 机型）
- [ ] 日志按天切割，保留 30 天

---

## 9. 性能标准目标

> 基准机型：2核4G 云服务器，MySQL + Redis + JVM 同机部署。

| 指标 | 目标值 | 测量方式 |
|---|---|---|
| 读接口 P95 | < 300 ms | Nginx access log `$request_time` 统计 |
| 列表接口（Feed/搭子发现）P95 | < 500 ms | 同上 |
| 写接口（发帖/发消息）P95 | < 400 ms | 同上 |
| 首页 Feed 首屏 TTFB | < 800 ms | 浏览器 Network |
| WS 消息端到端延迟 | < 500 ms | 客户端发送时间戳 − 接收时间戳 |
| 并发承载 | 注册用户 2 万 / 日活 3 千 / 峰值同时在线 800 | — |
| WS 长连接 | < 5,000（超过触发 ADR-004 阶段 2） | `WsSessionManager` 计数暴露到 health |
| 慢查询 | > 1s 的查询归零 | MySQL slow log，`long_query_time=1` |
| 单表行数 | < 500 万（超过考虑归档） | — |
| 前端首包 JS | < 500 KB (gzip)，**移动端 < 300 KB** | `npm run build` 产物统计 |
| 前端首屏 CSS | **< 120 KB (gzip)** | 同上 |
| 前端 LCP（校园网 4G） | **< 2.0 s** | 采用设计师 `UIUX.md` §13.5 的预算（比本文初稿的 2.5s 更严） |
| Web Font | **0 KB** | 系统字体栈，见 `design-tokens.json` 的 `typography.webFontBytes` |
| 构建时长 | 后端 < 3 min / 前端 < 2 min | CI 计时 |

**性能红线（写代码时就要避开）**
- 禁止循环内查库（N+1）。批量接口一律 `WHERE id IN (...)` 一次查回再内存组装
- 所有列表接口必须分页（游标 `CursorPage` 或 page），禁止无 LIMIT 查询
- 计数类更新一律事务内 `SET x = x ± 1`，禁止读-改-写
- 禁止在 WS handler 里做同步数据库写（会阻塞 Tomcat 线程）
- 新增索引遵循"高频查询字段 + 外键 + 排序字段"，MVP 不建复合索引除非有明确查询印证

---

## 10. 强制编码规则（P0，违反即打回）

1. **图标**：全项目仅用 `@element-plus/icons-vue@2.3.2`。尺寸只许 16 / 20 / 24px，通过 CSS 变量控制。**禁止任何 emoji 字符作为功能图标**（当前违规：`Chat.vue:150`、`Notify.vue:103`）。
2. **颜色**：禁止硬编码颜色值，唯一例外 `#fff` / `#000`。所有颜色走设计令牌（`docs/design/design-tokens.css` 为唯一准绳，`frontend/src/styles/tokens.css` 需与其对齐，见 OD-15）。当前违规：`Login.vue:171`、`Register.vue:148`、`Chat.vue:1027`。
3. **动效**：禁止弹跳/弹性缓动 `cubic-bezier(0.68, -0.55, 0.265, 1.55)`。用标准 ease-out。
4. **文案**：禁止空洞占位文案（"Welcome to Our App" / "Lorem ipsum" / "快来体验吧"）。所有面向用户的文案写具体信息。
5. **渐变**：禁止紫色→粉色渐变配色方案。
6. **Redis**：禁用 §6.5 清单中所有 6.0+ 指令与对应 Spring Data API。不确定就走 Lua。
7. **API 版本**：对外契约一律 `/api/v1/*`。后端内部路由保持现状，由 Nginx 做版本路由（见 ADR-008）。
8. **文件组织**：单文件 ≤ 300 行；单一职责；入口（Controller）只装配不放逻辑。
9. **事务**：`@Transactional` 只出现在 `service/impl/`，Controller 和 Mapper 层禁止出现。
10. **错误**：业务异常统一 `BusinessException` + `ResultCode`；禁止 `catch (Exception e) {}` 吞异常。
11. **断点**：只能用设计师的 6 档（640 / 768 / 1024 / 1280 / 1536）。**禁用 `el-col` 的 `:xs`/`:sm`/`:md`/`:lg`/`:xl` 响应式属性**，栅格一律 CSS Grid + `@media (--ys-bp-*)`。EP 内置断点与设计师断点完全错位，用了就是第二套体系。
12. **间距与触摸目标**：间距只允许 4px 网格（`4/8/12/16/20/24/32/40/48/64`），最小可点击区域 **44×44**，靠 padding 撑够而非放大视觉。移动端必须处理底部安全区。
13. **设计令牌**：`docs/design/design-tokens.css` 是唯一准绳。业务代码只写 `var(--ys-*)`，**禁止直接改 `--el-*`**（Bridge 段里的 `light-*` 系列是硬编码派生值，只改主变量会让 hover/active 色留在旧色系，见 ADR-009）。
14. **移动端视口高度**：禁用 `100vh`，一律 `100dvh`。

---

## 11. 端到端验证步骤（Phase 2 收尾执行）

> 规格不停在"实现完成"，停在"证明它对"。

**准备**
```bash
# 本地
mysql -h 127.0.0.1 -P 3307 -u root -p campus_social_v2 < sql/schema.sql   # 8.4 实例
redis-server                                                              # 5.0.14.1
cd backend && mvn -B clean package                                        # 必须 BUILD SUCCESS
cd frontend && npm ci && npm run build                                    # 必须构建成功
```

**核心成功流（注册 → 破冰 → 找搭子 → 看公告）**
1. 邮箱注册 → 收验证码 → 注册成功 → 拿到 access/refresh token
2. 设置兴趣标签（≥3 个）→ `GET /api/v1/tags` 能查到刚设的标签
3. 用账号 A 向**非好友**的账号 B 发私信 → 成功（验证 G1 免好友）
4. B 登录 → 收到 A 的消息 + WS 实时推送（验证 WS 单机广播）
5. A 继续向第 6 个新对象发消息 → 返回配额超限（验证 G1 频控）
6. B 举报 A → 管理员后台能看到举报 → 处理 → A 被限制（验证举报闭环）
7. 发布搭子卡片（标签=王者荣耀）→ 在"发现"页按该标签筛选能查到（验证 G2）
8. 两个不同账号报名同一搭子（`want_count=1`）→ 只有一个成功（验证防超卖）
9. 管理员发布公告并置顶 → 前台列表置顶项排第一 → 普通用户打开 → 未读数 −1（验证 G3）
10. 全员在线时发布公告 → 在线用户收到 WS 提示（验证 G3 推送）

**关键错误流（必须至少各跑一条）**
- 未带 token 访问 `/api/v1/buddy` → 401
- 非管理员访问 `/api/v1/admin/announcement` → 403 `NOT_ADMIN`
- 黑名单用户互发消息 → 明确拒绝文案
- 陌生人配额耗尽 → 429 + "今日破冰次数已用完" 具体文案
- refresh token 重用 → 401（已实现，回归）
- 上传 60MB 视频 → 413 或明确的大小超限提示

**环境一致性验证（本次特有的，不能省）**
- [ ] 在 **Redis 5.0.14.1** 上跑完上述全流程，无 `ERR unknown command`（这是踩过坑的地方）
- [ ] 在 **MySQL 8.4** 实例上跑通，而不仅是 9.0
- [ ] 生产服务器上用 `mysqldump` 从本地 9.0 导出 → 导入 8.4 → 数据一致（提前验证降级路径，别等到上线当天）

**移动端验证（若采纳 Q8 的方案 A 或 B，必须加跑）**
- [ ] 375px / 390px / 414px 三档视口下，19 个文件均无横向滚动条
- [ ] iOS Safari 与 Android Chrome 各一轮真机：`100dvh` 不被地址栏遮挡、键盘弹起不遮挡输入框、底部安全区正确
- [ ] 所有可点击元素实测 ≥ 44×44（用 Safari 审查元素量，不靠肉眼）
- [ ] 3 处固定宽弹窗在 375px 下不溢出（改为全屏或 92%）
- [ ] `npm run build` 后首屏 JS < 300KB gzip、CSS < 120KB gzip（**TD-21 未修则此项必挂**）
- [ ] Lighthouse 移动端（4G 节流）LCP < 2.0s

---

## 12. Phase 2 放行条件（硬性门禁）

Phase 2（详细设计 + 编码）开始前必须齐备，缺一不放行：

1. **`docs/api/openapi.yaml`** —— OpenAPI 3.0 规范，覆盖全部 `/api/v1/*` 端点。前端据此生成 TS 类型与 MSW Mock，后端据此实现。**这是前后端唯一契约来源。**
2. `docs/ARCHITECTURE.md` —— 本文（已完成）
3. `docs/decisions/ADR-001` ~ `ADR-008` —— 选型决策留痕（已完成）
4. D1 技术债（TD-01 ~ TD-10）有明确的修复计划与负责人
5. 服务器已购、域名备案已提交（备案未完成不影响编码，但影响真机联调）

---

## 13. 关联 ADR

| ADR | 主题 | 裁决 |
|---|---|---|
| ADR-001 | 复用 vs 重写 | 增量复用 |
| ADR-002 | 部署方案 | 云服务器单机直装 |
| ADR-003 | 图标库 | @element-plus/icons-vue 2.3.2 |
| ADR-004 | WebSocket 广播 | 单机内存，临界点后转 Redis PubSub |
| ADR-005 | 数据层版本锚定 | MySQL 8.4 LTS + Redis 5.0 语义编码 |
| ADR-006 | 验证码通道 | 邮箱优先，短信 v1.1 |
| ADR-007 | 支付 | MVP 不做 |
| ADR-008 | API 版本前缀 | 对外 /api/v1，Nginx 版本路由 |
| ADR-009 | 设计令牌唯一准绳与 `--ys-*` 接入 | `design/design-tokens.css` 唯一，旧 `tokens.css` 待迁移；含 EP Bridge 与断点落地规则 |

未决项见 `decisions/OPEN-DECISIONS.md`。移动端响应式方案见 §3 Q8。
