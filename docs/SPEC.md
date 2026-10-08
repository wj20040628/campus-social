# Spec — 友声 · 校园生活社区 v1.0（MVP）

> 生成日期：2026-10-08
> 基于：PRD v1.0 + ARCHITECTURE v1.0 + UIUX v1.0 + design-tokens v1.0 + ADR-001~009
> 状态：**已锁定**（用户已确认三文档，并已就 OD-17 / OD-19 拍板）
> 本文件是团队内部契约。之后的 Spec 生成、设计细化、开发、测试，**以本文件为唯一依据**。

---

## 0. 本轮已锁定的用户决策（不可推翻）

| 编号 | 决策项 | 用户裁定 | 影响 |
|---|---|---|---|
| OD-17 | 移动端响应改造范围 | **A 档：全量移动优先，14 人日**（19 个文件全量改造） | Phase 3 前端主工期以此排；后台管理页（AdminReview / AdminAudit）同样必须支持移动端，因为下一条 |
| OD-19 | 管理员是否在手机端处理举报 | **会，手机上审** | C 档（后台页保留桌面形态）不成立。`AdminReview.vue` 必须在手机上可用——举报处置有时效要求，管理员不在手机上响应会导致处置延迟 |
| OD-18 | 断点三处同步是否引入生成脚本 | **项目负责人裁定：接受手工同步 + 文档纪律，不引入 devDep** | 见 §10.4 同步纪律。引入构建脚本解决一个文档级漂移属过度设计 |

---

## 1. 产品定义

- **一句话描述**：面向在校大学生的一站式校园社交信息平台——**不用加好友就能搭话、校园的事一处看全**，替掉「班群 + 钉钉 + 校园墙 + 私加好友」的割裂流程。
- **目标用户**：在校本科生。课少闲暇多、手机里几十个群全部免打扰、想找同校的人打游戏吃饭爬山却不愿意（或不好意思）加好友。
- **核心问题**：微信/QQ 的「加好友才能聊天」门槛，把校园里大量「临时、轻量、一次性」的沟通需求逼成了社交负债。
- **产品价值观（写死，任何后续设计不得违反）**：**「不回应」必须是零成本、零社交负债的。** 这是与微信「添加即永久」的根本分野。

---

## 2. MVP 范围（锁定 —— 不在此列表的功能一律不做）

### 2.1 功能范围

| 优先级 | 编号 | 功能 | RICE | 验收标准摘要 |
|---|---|---|---|---|
| P0 | F1 | 移动端优先响应式改造（18 SFC 全量） | 10.0 | 375px 视口下无横向滚动；Top3 最难页面完成结构性改造；移动端 LCP < 2.0s |
| P0 | F2 | 校内身份归属与认证 | 10.0 | 未认证用户不能发起搭话、不能发布公告；认证后展示「已认证 · XX 大学」 |
| P0 | F3 | 免好友「搭话」 | 6.4 | 无关系即可发起；24h 未回静默归档；每日额度 5；三重护栏生效 |
| P0 | F5 | 官方公告通道 | 4.8 | 公告豁免免打扰；置顶生效；已读回执幂等；发布方可见「谁还没看」 |
| P0 | F11 | 新用户引导与种子内容 | 4.0 | 注册后 3 步引导；上线前 ≥50 条真实种子内容；空列表必有引导 + 行动按钮 |
| **门槛** | F4 | 内容安全闭环补强 | 6.0 | 举报可提交（幂等）；管理员**手机端**可处置；处理状态回执。**不是 MVP 功能排序项，是上线放行项——不做不放行** |

### 2.2 数据模型范围（含「先预留后释放」决策）

| 表 | 本轮动作 | 说明 |
|---|---|---|
| `interest_tag` | **实现** | 支撑 F11 引导第 2 步「选兴趣标签」。**这是 PM 文档内部的隐性冲突**：PRD 把 F6 兴趣标签体系列为 MVP+，但 F11 引导明确包含「选 3-5 个兴趣标签」，F11 是 MVP。故标签字典与用户标签关系**本轮必须实现**，只是「按标签找推荐」的发现页延后 |
| `user_tag` | **实现** | 同上 |
| `report` | **实现** | 归属 F4 上线门槛项 |
| `announcement` | **实现** | F5 |
| `announcement_read` | **实现** | F5 |
| `buddy_post` | **建表，不实现端点** | 归属 MVP+（F7）。本轮只出 DDL，避免第二批再来一次改表。**禁止**在 Phase 3 实现其 Service/Controller/页面 |
| `buddy_join` | **建表，不实现端点** | 同上 |
| `school_dept` | **实现** | 院系字典。F2/F11 第 1 步从自由文本改为字典 id，见 §6.1 |
| `school_grade` | **实现** | 年级字典。口径为入学年份（`2026级`），见 §6.1 |
| `user` | **ALTER 加 6 字段** | `school_id`、`verify_status`、`stranger_msg_enabled`、`onboarding_step`、`dept_id`、`grade_id`（见 §6.2） |

> **这是本轮最大的一致性纠偏**：三方文档都没有注意到 F11 与 F6 的范围冲突。若照原文档执行，会出现「引导要求选标签，但标签表被划到 MVP+ 没做」的死锁。

### 2.3 实体表总数核定

- 现状实测：**22 个** entity（`ls backend/src/main/java/com/campus/social/entity/*.java | wc -l` = 22）
- 本轮新增：7 张（`interest_tag` / `user_tag` / `report` / `announcement` / `announcement_read` / `buddy_post` / `buddy_join`）
- 终态：**29 张**
- **勘误**：`ARCHITECTURE.md` 第 623 行写「23 表 → 29 表（+7）」、第 818 行写「新增 7 张表（23 → 30）」，两处自相矛盾且基数均错。**以本 Spec 的 22 → 29 为准**。Phase 2 由架构师用 `openapi.yaml` 与 DDL 复核后回填确认。

---

## 3. 明确不做（Out-of-Scope — 锁定）

> 开发中有人提出下面这些，直接拒绝，不需要讨论。完整 14 条见 `PRD.md` §9。

| 不做的功能 | 原因 | 何时考虑 |
|---|---|---|
| App（iOS / Android） | 用户明确网页先跑起来；上架需软著+备案+商店审核，周期以月计 | Web DAU 稳定后 |
| 微信小程序 | 社交类目对个人主体基本不开放 | 有企业主体后 |
| 任何支付/充值/会员/虚拟币 | 门槛在主体不在技术；个人最多小微商户且线上场景不开。引入支付即引入退款纠纷 | 有执照后 |
| 直播 / 语音房 | 需《网络文化经营许可证》+ 7×24 人工审核，个人不可承担 | 不做 |
| 完全匿名发帖 / 匿名树洞 | Fizz 因匿名在多校被封；国内校园墙语言暴力 24.07%。本产品只做「校内实名背书 + 对外昵称展示」 | 不做 |
| 二手交易 / 跑腿 / 电商 | 引入资金流与线下纠纷，本轮无纠纷处理能力 | 不做 |
| 跨校 / 全国 Feed | 单校密度优先。40% 的活跃胜过 20 校 2% | DAU 破万后 |
| AI 智能匹配 / 推荐算法 | 冷启动无数据，算法无输入且不成熟。用规则（同一学校 + 标签重合 + 时间临近） | 数据量足够后 |
| **短信验证码** | 腾讯 2025-09-18 起停新增个人自用资质，阿里云同理（见 ADR-006） | 有企业主体后 |
| 课程表导入 / 空教室 / 成绩查询 | 超级课程表教训：一旦从工具切入，心智锁死为工具，社交做不起来 | 不做 |
| 话题热度榜 / 校园热搜 | 冷启动期榜单是空的，反而暴露冷清 | DAU 破 1000 |
| 私信语音/视频/已读回执/撤回 | 语音视频带来存储与审核成本；撤回与已读在半熟人校园场景制造社交压力，与「不回应零成本」价值观冲突 | 不做 |
| 私信端到端加密 | 与内容安全审核义务冲突，本轮无合规能力 | 不做 |
| 校友 / 毕业生长尾社交 | 稀释「同一时间同一空间」这个校园社交核心前提 | 不做 |

---

## 4. 技术架构（锁定 — 含版本锚定）

### 4.1 总原则

**增量复用，不重写。** 详见 `ADR-001`。

### 4.2 版本锚定（**必死锁，防止幻觉 API**）

#### 本地开发环境（实测）

| 组件 | 实测版本 | 处置 |
|---|---|---|
| OS | Windows 10 专业版 21H1 (build 19043) | 锁死，不支持 Docker Desktop |
| JDK | OpenJDK 17.0.19 LTS | 锁 17 |
| Maven | 3.9.15 | 锁 |
| Node | v22.22.2 | 锁 |
| MySQL Server | **9.0（EOL 2024-10-15）** | **加装 8.4 LTS 实例（端口 3307）作 dev 主库**（ADR-005） |
| Redis | **5.0.14.1 (Windows)** | 保留不动；**代码按 5.0 语义写** |

#### 后端（锁定，不改）

| 依赖 | 版本 |
|---|---|
| Spring Boot (parent) | **3.2.5** |
| Java | **17**（不升 21） |
| mybatis-plus-spring-boot3-starter | **3.5.7** |
| mysql-connector-j | **8.4.0**（用户明确要求，勿升 9.x） |
| jjwt (api/impl/jackson) | **0.12.5** |
| hutool-all | **5.8.27** |
| springdoc-openapi-starter-webmvc-ui | **2.5.0** |
| spring-boot-starter-actuator | **新增**，版本随 parent（TD-09 修复项） |

#### 前端（**锚 lock 实际解析版本，不锚 package.json 声明值**）

| 依赖 | package.json 声明 | lock 实际解析 | 处置 |
|---|---|---|---|
| vue | ^3.4.0 | **3.5.42** | 保持 caret |
| vite | ^5.4.0 | **5.4.21** | 保持 caret |
| element-plus | ^2.8.0 | **2.14.5** | **改为精确 `2.14.5`**（TD-12） |
| @element-plus/icons-vue | 未声明（传递依赖） | **2.3.2** | **提升为直接依赖，锁 `2.3.2`**（ADR-003） |
| pinia | ^2.2.0 | **2.3.1** | 保持 caret |
| vue-router | ^4.4.0 | **4.6.4** | 保持 caret |
| axios | ^1.7.0 | **1.20.0** | 保持 caret |
| dayjs | ^1.11.10 | **1.11.23** | 保持 caret |

> **锚定原则**：先确认 `package-lock.json` 实际解析结果，再按该版本 API 写代码。声明值带 caret 会漂移，不是可锚对象。

#### 生产环境（目标）

| 组件 | 版本 |
|---|---|
| OS | Ubuntu 22.04 LTS |
| JDK | Eclipse Temurin 17.0.x LTS |
| Nginx | 1.24 或 1.26（nginx 官方源） |
| MySQL Server | **8.4.x LTS** |
| Redis | **7.2.x**（生产跑 7，代码按 5.0 语义写 → 两边都安全） |
| Node（仅构建期） | 20/22 LTS |

### 4.3 部署

- **方案**：云服务器单机直装 JDK17 + Nginx + MySQL 8.4 LTS + Redis 7.2。**不用 Docker、不用 Serverless**（ADR-002）
- **不用 Docker 的真实理由（记住，不要传错）**：不是「服务器装不了 Docker」——Linux 装 Docker 完全没问题，与本机无 Docker Desktop 不冲突。理由是**本机永远跑不了 compose，那个 compose 文件无人验证，现在就已经是错的**（写 redis:7，本机是 5.0）。选一个永远验证不了的方案比保守方案更危险
- **月成本**：保守 **¥40**（服务器 ¥31.6 + 域名 ¥7.5）；新人首年 **¥25**，续费翻倍
- **备案**：¥0，但占 **5–20 工作日**（可能打回补材料）。**与编码并行，不要串行**
- **容灾**：单机 = 单点故障。每日 `mysqldump` 异地备份，**必须演练过一次恢复**才算数

### 4.4 认证

JWT 双 token：`access` 15min + `refresh` 7d；refresh jti 存 Redis，刷新即删旧，重用即 401。两个 secret 各自 ≥64 字节强随机且互不相同。

---

## 5. API 端点清单（锁定 —— 开发时以此为唯一依据）

> **契约文件**：`docs/api/openapi.yaml`（OpenAPI 3.0）由架构师在 Phase 2 产出，前端据此生成类型、后端据此实现。**该文件是 Phase 2 放行门禁第 1 条。**
> 所有端点对外部统一 `/api/v1/*` 前缀（ADR-008）。

### 5.1 F3 免好友搭话（G1）

**现状（重要）**：`ChatServiceImpl.send()` **已不校验好友关系**（实测 `ChatServiceImpl.java:91-143`）。免好友发起会话能力**已存在**，本轮只补护栏。

```
POST   /api/v1/report                          提交举报（幂等，重复提交返回已记录）
GET    /api/v1/admin/reports?status=&cursor=   举报列表（管理员）
POST   /api/v1/admin/reports/{id}/handle       处理举报（封禁/驳回/忽略）

POST   /api/v1/chat/send                       【改造】加三重护栏：
                                               1. 黑名单双向拦截（relation.type=3）
                                               2. 陌生人日配额（Redis 滑动窗口，超限 429）
                                               3. 敏感词过滤（复用 SensitiveWordFilter）
GET    /api/v1/chat/stranger-quota             查询今日剩余破冰额度
```

**配额（配置进 application.yml，不硬编码）**：
- `stranger.daily-new-peer = 5`（每天最多向 5 个新对象发起首条）
- `stranger.per-peer-daily = 20`（对同一陌生人每天最多 20 条）

### 5.2 G2 搭子（**本轮仅建表，下列端点不实现**）

```
GET    /api/v1/tags                            兴趣标签树（按 category 分组）   ← 本轮实现（支撑 F11）
PUT    /api/v1/user/tags                       设置我的兴趣标签（批量覆盖，≤8）   ← 本轮实现（支撑 F11）
GET    /api/v1/user/{id}/tags                  查看他人标签                     ← 本轮实现
────────────── 以下端点本轮【不实现】，仅登记预留 ──────────────
POST   /api/v1/buddy                           发布搭子卡片
GET    /api/v1/buddy?tagId=&status=&cursor=    发现/推荐列表
GET    /api/v1/buddy/{id}                      详情
POST   /api/v1/buddy/{id}/join                 报名（幂等 + 乐观锁防超卖）
DELETE /api/v1/buddy/{id}/join                 取消报名
GET    /api/v1/buddy/mine?type=                我发布的 / 我报名的
PATCH  /api/v1/buddy/{id}/status               关闭/重开
```

### 5.3 F5 官方公告（G3）

```
POST   /api/v1/admin/announcement              发布公告（草稿 / 直接发布）
PATCH  /api/v1/admin/announcement/{id}         编辑（置顶/取消置顶/上/下架）
GET    /api/v1/admin/announcement?status=&cursor=  后台列表
GET    /api/v1/announcement?category=&cursor=&size= 前台列表（置顶优先）
GET    /api/v1/announcement/{id}               详情（返回 is_read）
POST   /api/v1/announcement/{id}/read          已读回执（幂等）
GET    /api/v1/announcement/unread-count       未读公告数（红点）
```

**推送策略（避免过度设计）**：发布时**不**给全员写 notify 行；通过 `WsPushService` 向**当前在线**用户推一条轻量提示（新增 `notify.type = 6 公告`，内容为标题 + id）；离线用户靠 `unread-count` 红点拉取。置顶靠列表排序，不做额外推送。

### 5.4 F2 校内身份归属与认证（**架构文档缺失，Phase 2 补充细化**）

```
POST   /api/v1/user/school-profile             设置/更新 学校 / 学院 / 年级
POST   /api/v1/user/verify/campus-email        发起校园邮箱验证码（域名白名单校验）
POST   /api/v1/user/verify/campus-email/confirm 确认验证码
POST   /api/v1/user/verify/student-no          学号 + 姓名后两位（冷启动人工核验兜底 → 进 review_task）
GET    /api/v1/user/verify/status              当前认证状态
```

### 5.5 F11 新用户引导

```
GET    /api/v1/onboarding/tags                推荐标签（首次梯度，数量 ≤60）
POST   /api/v1/onboarding/complete            完成引导（批量写标签 + 批量关注，幂等）
GET    /api/v1/onboarding/recommend-users     推荐关注的人
GET    /api/v1/onboarding/recommend-circles   推荐圈子
```

---

## 6. 数据库表清单（锁定）

### 6.1 新增表（9 张）

通用字段约定（所有新表必带）：`id BIGINT AUTO_INCREMENT`、`create_time DATETIME DEFAULT CURRENT_TIMESTAMP`、`update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP`、`is_deleted TINYINT DEFAULT 0`。逻辑删除沿用 MyBatis-Plus 全局配置（`application.yml:60-64`，字段 `isDeleted`）。

| 表名 | 核心字段 | 索引 | 关联 | 本轮动作 |
|---|---|---|---|---|
| `interest_tag` 标签字典 | `id, name, category(1兴趣 2游戏 3运动 4学习 5生活), sort, use_count` | `uk_name(name)` | — | 实现（人工维护 30–60 个，不做 UGC） |
| `user_tag` 用户-标签 | `id, user_id, tag_id` | `uk_user_tag(user_id, tag_id)`；`idx_tag(tag_id)` | user / interest_tag | 实现 |
| `report` 举报 | `id, reporter_id, target_type(1用户 2帖子 3评论 4聊天消息 5搭子), target_id, reason_type(1骚扰 2广告 3色情 4诈骗 5其他), reason_text, status(0待审 1成立 2驳回), handler_id, handle_time` | `uk_report(reporter_id, target_type, target_id)` 防重复举报；`idx_status_time(status, create_time DESC)` | user | 实现 |
| `announcement` 公告 | `id, title, content, category(1教务 2后勤 3活动 4社团 5紧急 6其他), publisher_id, school_id(默认1，预留多校), is_top(0/1), top_expire_time, status(0草稿 1已发布 2已下架), publish_time, read_count` | `idx_school_status_top_time(school_id, status, is_top DESC, publish_time DESC)`；`idx_category(category, status, publish_time DESC)` | user | 实现 |
| `announcement_read` 已读回执 | `id, announcement_id, user_id, read_time` | `uk_ann_user(announcement_id, user_id)` 幂等；`idx_user(user_id, read_time)` | announcement / user | 实现 |
| `buddy_post` 搭子卡片 | `id, user_id, tag_id, title, content, want_count, joined_count, status(0招募中 1已满 2已关闭 3下架), expire_time` | `idx_tag_status_time(tag_id, status, create_time DESC)`；`idx_user(user_id)` | user / interest_tag | **仅建表** |
| `buddy_join` 报名 | `id, buddy_id, user_id, status(0待确认 1已加入 2已拒绝 3已退出)` | `uk_buddy_user(buddy_id, user_id)` 幂等；`idx_user(user_id, status)` | buddy_post / user | **仅建表** |
| `school_dept` 院系字典 | `id, name, school_id(默认1), sort, enabled(1启用 0停用)` | `uk_school_name(school_id, name)` 种子幂等基础；`idx_school_enabled_sort(school_id, enabled, sort, id)` | — | 实现（人工维护 55 个，不做 UGC） |
| `school_grade` 年级字典 | `id, name(如 2026级), enroll_year, sort, enabled` | `uk_name(name)`；`idx_enabled_sort(enabled, sort, id)` | — | 实现（见下方「年级字典口径」） |

#### 为什么院系与年级必须建字典表（后端增补申请，team-lead 已批准）

F2 校内认证与 F11 引导第 1 步原本让用户自由输入 `dept` / `grade`。**自由文本方案不可接受**：用户 A 填「计算机学院」、用户 B 填「计科」，数据库里是两个不同字符串，而 AC-14（同院系推荐/筛选）依赖 `user.dept` 相等判断——**会把同院系的人漏掉一半**。真实用户输入自由度无法收敛。

因此改为字典 id 授权：**`dept_id` / `grade_id` 是匹配与筛选的权威依据，`user.dept` / `user.grade` 两个字符串列保留为名称快照**，供既有列表查询（推荐理由、帖子作者卡、搜索结果、`UserVO`）直接读取，避免大范围改查询引入 N+1。

**为什么 `school_dept` 带 `school_id` 而 `school_grade` 不带**（不对称是有意的）：院系是学校特有设置，多校时不必回头改主键与唯一键；年级是入学年份，跨校同年级天然可比。

**`enabled` 与 `is_deleted` 的分工**：`enabled=0` = 学校撤销/合并院系、毕业年级退役，**不再让新用户选，但历史数据仍可解析出名称**（老用户主页不会变空白）；`is_deleted=1` = 彻底删记录。

#### 年级字典口径 = 入学年份（team-lead 已拍板，选方案 A）

字典存 `2026级` / `2025级` 这类**入学年份口径**，不是 `大一` / `大二` 这类学习阶段口径。

理由：**「2026级」是身份，「大三」是状态**。用户对「你是哪届」的认同远强于「你当前几年级」——「26 级的」是自我标签，「大三」每年要变一次。若字典改成阶段口径，用户毕业两年后回来系统显示「大四」，是荒谬的。

连带好处：字典**只增不删**，`2020级` 及更早永久 `enabled=0` 留着回显，一年只加 1 行，不存在「每年新增毕业态」的膨胀问题。

**存量数据不回填（team-lead 已裁定不执行）**。目标库实测：4 条dept 非空的存量记录，名称精确匹配命中 **0 条**，两条根因——

1. **年级口径不同且不可逆**：存量 `grade` 存 `大三`/`大二`/`大一`，字典存 `2026级`。存量库**没有入学年份字段**，无法反推。
2. **院系是简称对官方全称**：存量「计算机学院」对应字典「计算机科学与技术学院」。

**禁止用 `LIKE` 模糊匹配提高命中率**：存量「艺术学院」字面最近的字典项是「**设计艺术学院**」，模糊匹配会把这个用户静默归错到另一个院系。这种错不报错，只表现为推荐结果莫名其妙，极难定位。**精确匹配是唯一正确选择，哪怕命中 0 条。**

回填为 0 命中在当前阶段**零成本**：存量用户下次进引导页重选一次即自然补齐，2 个空号测试账号根本不会走到引导页。代码侧已对 `dept_id IS NULL` 做名称回退（`UserMapper#selectRecommendUsers` 的 `dept_id IS NULL` 分支、`DictService#hasDept/hasGrade`），不会被误判为「未填写」，推荐也不降级。

### 6.2 ALTER 现有表

| 表 | 新增字段 | 用途 |
|---|---|---|
| `user` | `school_id BIGINT DEFAULT 1` | F2 校内归属，预留多校 |
| `user` | `verify_status TINYINT DEFAULT 0`（0未认证 1已认证 2审核中 3驳回） | F2 |
| `user` | `stranger_msg_enabled TINYINT DEFAULT 1` | **F3 强制护栏**：关闭后陌生人发送直接返回「对方已关闭陌生人消息」。**没有这个开关，破冰功能上线即变骚扰工具** |
| `user` | `onboarding_step TINYINT DEFAULT 0`（0未开始 1已填归属 2已选标签 3已完成） | F11 引导进度。**不是为了存进度，而是为了可判别**——见下方「为什么 onboarding_step 不可省」 |
| `user` | `dept_id BIGINT NULL` | 院系字典 id，指向 `school_dept.id`。**匹配与筛选的权威依据**。存量行为 NULL，靠 `user.dept` 名称回退兼容 |
| `user` | `grade_id BIGINT NULL` | 年级字典 id，指向 `school_grade.id`。同上 |

#### 为什么 `onboarding_step` 不可省（后端增补申请，team-lead 已批准）

AC-18 只约束第1 步（`dept` 与 `grade` 非空），这一条可以从现有字段推导。但 `POST /onboarding/complete` 的**幂等**需要明确的「已完成」判据：

- 用户 A：0 标签 + 0 关注
- 用户 B：第 3 步一个都没选，直接点了完成

这两类用户在现有字段上**完全同构，无法区分**。把 B 当成"没完成"会让他反复被推进引导；当成"已完成"则引导数据是空的。

考虑过的替代方案：**用 Redis 键记录**。不可靠 —— Redis 无持久化保证，服务重启后用户会被重新推进引导流程，一个已经完成引导的老用户会突然又看到 3 步引导。

代价：1 个 TINYINT 列、1 字节、不新增表、不需要 join。批准。

### 6.3 两个「为什么」（不许问第二遍）

- **为什么 `buddy_post` 不复用 `activity`**：`activity` 有报名截止时间 + 活动状态机 + 线下场地语义；搭子卡片是轻量招募（"今晚王者缺一个"），语义不同，硬塞会让两张表都变脏。**但报名防超卖逻辑必须复用**——照抄 `activity_signup` 的「INSERT 幂等键 + `UPDATE ... WHERE joined_count < want_count` 乐观锁」同事务写法。
- **为什么公告不用「一人一条 notify」**：写入放大 1 万倍（1 万用户 × 100 公告 = 100 万行），且已读更新要 UPDATE 1 万行。`announcement_read` 只在实际有人读时写一行，未读数用 count 查询而非全量写入。

---

## 7. 页面清单（锁定）

现有 router 共 15 个路由（`frontend/src/router/index.js`）。本轮 12 个页面改造 + 4 个新增页面。

### 7.1 移动端改造分工（`ARCHITECTURE.md` §3 Q8.6，19 文件逐个评过）

| 档 | 文件 | 行数 | 人日 |
|---|---|---|---|
| **重** | `views/Chat.vue` | 1072 | 2.0 |
| **重** | `layouts/MainLayout.vue` | 229 | 1.5 |
| **重** | `views/CircleDetail.vue` | 372 | 1.5 |
| 中 | `AdminReview.vue` / `AdminAudit.vue` / `Home.vue` / `PostDetail.vue` / `Publish.vue` / `Profile.vue` / `Circles.vue` / `Login.vue` / `Register.vue` | — | 0.4 各 |
| 轻 | `Notify.vue` / `Search.vue` / `Settings.vue` / `TopicDetail.vue` / `ActivityDetail.vue` + 组件/基建 | — | 0.2 各 |

改造页面小计 10.6 人日 + 一次性基建 3.5 人日 = **14.1 人日（取整 14）**

### 7.2 三个「结构性改造」页面（不是加 CSS，必须重做结构）

| 页面 | 硬编码证据 | 移动端目标形态 |
|---|---|---|
| `views/Chat.vue` | `:5` `el-aside width="280px"`、`:695` `height: calc(100vh - 60px)`、`:85` `el-dialog width="380px"` | **双栏主从 → 单栏路由切换**（列表页 ↔ 会话页）。`100vh` 在移动浏览器被地址栏遮挡，改 `100dvh`。处理键盘弹起压缩输入区 |
| `layouts/MainLayout.vue` | `:4` `el-aside width="220px"`、`:139,142` `min-height:100vh`、sticky topbar 56px | 新增 **TabBar（56px，含 iOS 安全区）** + 顶栏；桌面保留 220px 侧栏，两套外壳同组件切换。**所有页面继承它，改错一处全站受影响** |
| `views/CircleDetail.vue` | `:105` `el-dialog width="480px"`、`:115,118` 2 个 `el-date-picker` | 480px 弹窗在 375px 视口必溢出 → 改全屏 / 底部抽屉 + `label-position="top"` |

> **行数不等于难度**：`PostDetail.vue` 有 485 行（比 CircleDetail 长），但它是线性内容流，改造量反而更低。结构决定难度。

### 7.3 新增页面

| 页面 | 路由 | 核心组件 | 对应 API |
|---|---|---|---|
| `views/Announcement.vue` | `/announcement` | 公告卡片列表、置顶条、分类筛选、已读确认按钮 | §5.3 前台三端点 |
| `views/AnnouncementDetail.vue` | `/announcement/:id` | 公告正文、已读回执、发布主体徽章 | `GET /api/v1/announcement/{id}` |
| `views/AdminAnnouncement.vue` | `/admin/announcement` | 后台发布/编辑/置顶/上下架、**手机端可用**（OD-19） | §5.3 admin 三端点 |
| `views/Onboarding.vue` | `/onboarding` | 3 步引导（选学校学院年级 → 选 3-5 标签 → 关注推荐），第 1 步不可跳过 | §5.5 |

### 7.4 自研移动端组件（约 200 行，禁止引入 Vant）

`src/components/mobile/TabBar.vue` / `ActionSheet.vue` / `SafeArea.vue`

---

## 8. 设计 Token（锁定）

> **唯一准绳：`docs/design/design-tokens.css`**（ADR-009）。历史文件 `frontend/src/styles/tokens.css`（46 行）**降级为待迁移**，Phase 3 前端第一个 PR 删除其重定义部分并改为 import 设计师 Token。

| 项 | 值 |
|---|---|
| 主色 | **友声青 `#0E7490`**（`--ys-accent`，白字/白底对比度均 **5.36:1**） |
| 主色 hover / active | `#0A5E75`（7.31）/ `#084C5F`（9.51） |
| 主色浅底 | `#E2F0F4`（accent 落其上 4.59） |
| 正文字色 | `#16212E`（15.01） |
| 次要文字 | `#5A6B7E`（5.05） |
| 元信息文字 | `#5F6E83`（4.79，初版 `#7A8899` 实测仅 3.34 不达标，已修正） |
| 字体 | **零 Web Font 下载（0KB）**。系统字体栈：iOS 苹方 / 安卓鸿蒙 / Windows 雅黑 + 层级靠字重字号字距建立。**理由：UGC 产品字符集不可穷举，子集化无效**（思源 8-16MB，漏字出补丁感） |
| 图标库 | **`@element-plus/icons-vue@2.3.2`**（ADR-003）。294 个图标，1024 viewBox + `fill=currentColor`。尺寸 **16 / 20 / 24px** 三档 |
| 自绘补齐 | EP 缺 **thumbs-up / shield / send** 三个语义 → `src/icons/` 自绘**补齐**，必须同为 1024 viewBox + fill 风格 |
| 断点 | **640 / 768 / 1024 / 1280 / 1536**（`UIUX.md` §6.1 + `design-tokens.json` breakpoint 段） |
| 主题 | 浅色。**暗色模式不进 MVP**，Token 已按语义命名预留，后续加 `data-theme="dark"` 即可 |
| 对标 | Notion 的分区秩序 + **校园公告栏物理隐喻**（不是照搬某个 App） |

### 8.1 三条设计红线（可自动检查，违反即打回）

1. **找搭子必须以「具体邀约」为单位，不以「人」为单位**：头像 ≤36px、昵称 13px，最显眼一行是「周五下午三点三号场，缺两个人，来吗」。以「人」为单位（大头像+昵称+年龄+打招呼）会立刻滑向颜值社交变陌陌，女生体验恶化，「校园」这个限定词失效
2. **禁止 `:fullscreen` 之外的固定宽弹窗**（380/480/420px 三处全部改造）
3. **`rg -n "7C3AED|A855F7|9333EA|EC4899" frontend/src/ docs/` 必须零命中**（仅允许出现在本文件这条禁止规则本身）

### 8.2 「我们不做 XX 感」

不做小红书感 / 不做 Soul 探探感 / 不做微信 QQ 感 / 不做 AI 模板感 / 不做可爱校园 App 感。理由见 `UIUX.md` §2.3。

---

## 9. 验收标准（锁定 —— QA 测试时以此为唯一依据）

> EARS 格式：While / When / If / Where + 系统 + 必须/应该 + 行为。

| 编号 | 功能 | EARS 格式验收标准 | 优先级 |
|---|---|---|---|
| AC-01 | F3 搭话 | While 用户 A 与用户 B 无任何 relation 关系，When A 在 B 的主页点击「搭句话」并输入 ≤50 字内容，系统**必须**生成一张有效期 24 小时的纸条并出现在 B 的消息页独立分组，**且不得**自动创建任何 relation 记录 | P0 |
| AC-02 | F3 搭话 | If B 在 24 小时内未回应，系统**必须**静默归档纸条，且**必须不**向 A 发送任何「已拒绝/已过期」提示 | P0 |
| AC-03 | F3 搭话 | If B 点「不感兴趣」，系统**必须**立即销毁纸条并在 7 天内拦截 A 向 B 的再次搭话 | P0 |
| AC-04 | F3 频控 | If 用户当日已向 5 个新对象发起搭话，When 其向第 6 个对象发起，系统**必须**返回 429 及明确文案 | P0 |
| AC-05 | F3 护栏 | If 接收方 `stranger_msg_enabled = 0`，When 陌生人发送消息，系统**必须**拒绝并返回「对方已关闭陌生人消息」 | P0 |
| AC-06 | F3 审核 | While 搭话内容触发敏感词，系统**必须**拦截发送并写入 `review_task` | P0 |
| AC-07 | F5 公告 | While 一条公告处于置顶状态，前台列表**必须**将其返回在首位直到 `top_expire_time` | P0 |
| AC-08 | F5 公告 | If 用户设置了免打扰，公告消息**必须仍然**推送（豁免免打扰） | P0 |
| AC-09 | F5 回执 | When 同一用户重复提交同一公告的已读回执，系统**必须**返回成功且不产生重复记录（`uk_ann_user` 幂等） | P0 |
| AC-10 | F5 回执 | While 发布主体为已认证账号，系统**必须**提供「谁还没看」名单，并对未读人群支持一键提醒 | P0 |
| AC-11 | F2 认证 | If 用户未通过校内认证，系统**必须**禁止其发起搭话与发布公告 | P0 |
| AC-12 | F2 隐私 | While 对外展示任何认证用户，系统**必须只**显示昵称 + 头像 +「已认证 · XX 大学」，**必须不**显示真实姓名与学号 | P0 |
| AC-13 | F4 举报 | When 同一用户对同一目标重复提交举报，系统**必须**返回「已记录」而非创建新记录 | 门槛 |
| AC-14 | F4 审核 | While 存在 status=0 的举报记录，When 管理员在**手机视口（375px）**下打开审核页，系统**必须**可完成处置且不出现横向滚动（OD-19） | 门槛 |
| AC-15 | F1 移动 | While 视口宽度为 375px，所有已改造页面**必须**无横向滚动条，所有可点击元素热区 **≥44×44px** | P0 |
| AC-16 | F1 移动 | While 视口为 375px，`Chat.vue` **必须**呈现单栏结构而非双栏主从 | P0 |
| AC-17 | F1 性能 | When 以移动端 4G 条件加载首页，LCP **必须** < 2.0s，首屏 JS **必须** < 300KB gzip | P0 |
| AC-18 | F11 引导 | While 用户未完成第 1 步（学校/学院/年级），系统**必须**阻止其跳过后续直接进入 Feed | P0 |
| AC-19 | F11 空态 | If 任意列表为空，系统**必须**展示引导文案 + 一个可点击行动按钮，**必须不**出现空白页 | P0 |
| AC-20 | 安全 | When 非管理员访问 `/api/v1/admin/**`，系统**必须**返回 403 `NOT_ADMIN` | P0 |

---

## 10. 边界与约束

### 10.1 Redis 5.0 硬约束（**已踩过一次坑，必读**）

**规则：所有 Redis 代码必须能在 Redis 5.0.14.1 上跑通。** 生产用 7.x 是向下兼容的选择，不是放开口子。

禁用 API：`opsForValue().getAndDelete()` / `getAndExpire()`、`opsForZSet().randomMember()` / `distinctRandomMembers()`、`RedisTemplate.copy()`、`expire(key, ttl, ExpiryPolicy.*)`、以及任何依赖 `GETDEL`/`GETEX`/`LPOS`/`ZRANDMEMBER`/`SMISMEMBER`/`COPY`/`EXPIRE NX` 的调用。

安全 API：`get` / `set` / `delete` / `increment` / `setIfAbsent(key, val, Duration)` / `expire(key, ttl)` / `execute(RedisScript, keys, args)`。

**所有原子读删一律走 Lua，集中放 `util/RedisLua.java`，禁止散落。** 5 段脚本（GETDEL 替代 / GETEX 替代 / SET KEEPTTL 替代 / 滑动窗口限频 / 分布式锁释放）见 `ARCHITECTURE.md` §6.5。**纪律：新增任何 Redis 调用前先问「Redis 5.0 有这条指令吗」。不确定就走 Lua。**

### 10.2 MySQL 版本约束

- 生产 **8.4.x LTS**；本地现状 9.0（EOL 2024-10-15，不再收任何安全补丁）
- **9.0 → 8.4 是降级**，官方要求 `mysqldump` 逻辑导出 + 导入，**不支持原地降级**
- 建议本地加装 8.4 LTS 实例（端口 3307）作 dev 主库，避免「本地 9.0 跑通、生产 8.4 报错」
- 连接器保持 8.4.0；字符集 `utf8mb4` / `utf8mb4_unicode_ci`

### 10.3 编码硬规则（写进 Spec 强制层）

1. **禁止 emoji 作功能图标**（例外：UGC / 即时消息正文）
2. **一套图标库不混用**：只用 `@element-plus/icons-vue@2.3.2` + `src/icons/` 自绘补齐（同 1024 viewBox + fill 风格）
3. **禁用 Element Plus 响应式属性** `el-col` 的 `:xs/:sm/:md/:lg/:xl`。**原因：EP 2.14.5 内置断点是 768/992/1200/1920，与设计师 640/768/1024/1280/1536 完全错位**（EP 的 sm=768 是设计师的 md；EP 的 md=992 设计师根本没有这一档）。栅格一律 CSS Grid + `@media (--ys-bp-*)`
4. 禁止硬编码颜色（例外 `#fff` / `#000`）
5. 禁止弹跳缓动 `cubic-bezier(0.68, -0.55, 0.265, 1.55)` 及任何 `y<0` 或 `y>1` 的控制点
6. 禁止空洞占位文案（"Welcome to" / "Lorem ipsum" / "敬请期待" / "暂无数据"）
7. 单文件 ≤300 行；依赖只向下（controller → service → mapper）
8. Redis 调用见 §10.1

### 10.4 断点同步纪律（OD-18 裁定）

断点值存在于三处：`design-tokens.json`（源）、`design-tokens.css` 的 `@custom-media`、`useBreakpoint.js`。

**裁定：接受手工同步 + 文档纪律，不引入生成脚本**——为一个文档级漂移问题增加构建依赖属过度设计。

纪律：
- `design-tokens.json` 是**唯一源**
- 修改断点必须**同时改三处**，并在 PR 描述里写明三处都已改
- `useBreakpoint.js` 从 `design-tokens.json` **import 值**而非写死
- CSS 侧：`@custom-media` 不可引用 CSS 变量（规范限制），只能写死 px——因此 **Phase 2 结束时须跑一次三处值一致性检查**

### 10.5 其他边界

- 不支持 IE；目标浏览器 Evergreen + 微信内置浏览器
- 响应式断点：640 / 768 / 1024 / 1280 / 1536，**手机 <640px 为默认设计基准**
- 性能目标：移动端 **LCP < 2.0s**（以设计师 §13.5 为准，架构文档原写 2.5s 已修正）、首屏 JS < 300KB gzip
- WebSocket：单机内存 Session，**在线破 5000 或部署第 2 个实例时必须改 Redis PubSub**。不改的症状是**沉默故障**——一半人收不到消息且看不出哪里坏

---

## 11. 内嵌已知坑（防止重蹈覆辙）

| # | 坑 | 技术栈指纹 | 根因 | 修法 | 归属 |
|---|---|---|---|---|---|
| 1 | 验证码全链路挂掉 | Redis 5.0 / Spring Data Redis | `opsForValue().getAndDelete()` 底层发 Redis 6.2 的 GETDEL，5.0 抛 `ERR unknown command` | 改为 Lua 脚本原子取出并删除（已修，勿回退） | 已修，回归验证 |
| 2 | 新写 Redis 限频 / 原子操作时报 `ERR unknown command` | Redis 5.0 / Spring Data Redis | 坑 1 的同族：误用了 6.0/6.2/7.0 新增指令 | 一律走 `util/RedisLua.java` 的 5 段脚本，新增前先问「5.0 有吗」 | Phase 3 |
| 3 | 本地 9.0 跑通、生产 8.4 报错 | MySQL 9.0 vs 8.4 | 9.0 是 Innovation 分支，行为与 LTS 有差异 | 本地加装 8.4 LTS 实例（3307），上线前演练完整导出导入 | Phase 3/4 |
| 4 | 移动端做得漂亮但永远压不到 300KB | Vite 5.4 + Element Plus 2.14.5 | **TD-21**：`main.js:3-6` 全量注册 + `vite.config.js:20-31` 同时配按需 + `manualChunks:32-35` 强制整包 chunk，三者打架 | 删 `main.js` 的 `app.use(ElementPlus)` 与 `element-plus/dist/index.css`；`manualChunks` 去掉 element-plus 项。**0.3 人日**。不修则 §9 AC-17 不可能达标 | Phase 3 前端第一个 PR |
| 5 | 元素从不生效 | Element Plus + 自定义 Token | CSS 变量**后定义者胜**，`main.js` 引入顺序错了是沉默故障 | main.js 引入顺序见 ADR-009 §「引入顺序」（不可调换） | Phase 3 |
| 6 | 两套 `--el-color-primary` | 设计师 Token + EP 默认 | 历史 `styles/tokens.css` 与 `design-tokens.css` 并存，且每个语义色要覆盖 7 个变量（1 主 + 6 派生） | 唯一准绳 ADR-009；历史文件 Phase 3 首个 PR 迁移 | Phase 3 |
| 7 | `theme-color` 与设计师主色不一致 | `index.html:8` | Token 双源的第 3 个实例 | 改为 `design-tokens.json` 的 `color.accent` | Phase 3 |
| 8 | 断点语义错位 | EP 2.14.5 vs 设计师 | EP 768/992/1200/1920 vs 设计师 640/768/1024/1280/1536 | 禁用 `el-col` 响应式属性（§10.3 第 3 条） | Phase 3 |
| 9 | `@element-plus/icons-vue` 版本漂移 | 传递依赖 | 未显式声明，跟着 EP 走 | 提升为直接依赖锁 2.3.2（ADR-003） | Phase 3 |
| 10 | CORS / WS origin 全开 | `WebMvcConfig.java:59-66`、`WebSocketConfig.java:29` | dev 方便留的后门 | 收窄为实际域名，从环境变量注入 | Phase 3 |
| 11 | 团队图「谁都能发」 | `ChatServiceImpl:91-143` | **设计如此**（已不校验好友关系），但本轮要开放给陌生人 | 补三重护栏 + `stranger_msg_enabled` 开关后才能放开 | Phase 3 |

---

## 12. 端到端验证步骤（Spec 锁定的最后一项）

```bash
# ── 0. 前置：本地 MySQL 8.4 LTS（端口 3307）+ Redis 5.0 已起 ──

# ── 1. 后端构建与启动 ──
cd backend && mvn -q clean package -DskipTests
java -jar target/*.jar
# 断言：日志出现 "Started CampusSocialApplication"
curl -s http://localhost:8080/actuator/health     # 断言：{"status":"UP"}

# ── 2. 核心成功流：免好友搭话 ──
# 2.1 两个无 relation 的账号分别登录，取得 tokenA / tokenB
curl -X POST http://localhost:8080/api/v1/auth/login -H "Content-Type: application/json" \
  -d '{"username":"stuA","password":"..."}'
# 断言：返回 200 + accessToken

# 2.2 A 向 B 发起搭话（无任何好友关系）
curl -X POST http://localhost:8080/api/v1/chat/send -H "Authorization: Bearer $tokenA" \
  -H "Content-Type: application/json" -d '{"toUserId":<B的id>,"content":"今晚王者缺一个，来吗"}'
# 断言：返回 200；DB 中 relation 表【无】新增记录（AC-01）

# 2.3 查询剩余额度
curl -s http://localhost:8080/api/v1/chat/stranger-quota -H "Authorization: Bearer $tokenA"
# 断言：remaining = 4

# ── 3. 关键错误流 ──
# 3.1 超配额（连续向第 6 个新对象发起）
# 断言：返回 429 + 明确文案（AC-04）
# 3.2 接收方关闭陌生人消息后发送
# 断言：返回错误码 + "对方已关闭陌生人消息"（AC-05）
# 3.3 同一用户重复举报同一目标
curl -X POST http://localhost:8080/api/v1/report -H "Authorization: Bearer $tokenB" \
  -H "Content-Type: application/json" -d '{"targetType":1,"targetId":<A的id>,"reasonType":1}'
# 第二次执行 => 断言：返回「已记录」，DB 中 report 表不新增行（AC-13）
# 3.4 非管理员访问 admin 端点
curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/api/v1/admin/reports -H "Authorization: Bearer $tokenA"
# 断言：403（AC-20）

# ── 4. 公告流 ──
curl -X POST http://localhost:8080/api/v1/admin/announcement -H "Authorization: Bearer $adminToken" \
  -H "Content-Type: application/json" -d '{"title":"选课通知","content":"...","category":1,"isTop":1}'
# 断言：201
curl -s "http://localhost:8080/api/v1/announcement?size=10" -H "Authorization: Bearer $tokenA"
# 断言：该条位于首位（AC-07）
curl -X POST http://localhost:8080/api/v1/announcement/<id>/read -H "Authorization: Bearer $tokenA"
# 再执行一次 => 断言：幂等，announcement_read 无重复行（AC-09）

# ── 5. 前端构建（移动端预算） ──
cd frontend && npm run build
# 断言：dist 中 element-plus 未被整包打入（TD-21 修复后），首屏 JS gzip < 300KB（AC-17）

# ── 6. 移动端视觉验证（375px 视口） ──
# 断言：无横向滚动；所有可点元素热区 >=44x44；Chat.vue 呈单栏（AC-15/AC-16）

# ── 7. 重启验证（排掉内存状态依赖） ──
# 杀进程重启后重跑第 2 步核心成功流，断言仍通过
```

---

## 13. 变更记录

| 日期 | 变更内容 | 原因 | 影响范围 |
|---|---|---|---|
| 2026-10-08 | Spec v1.0 初版 | Phase 1 三文档通过 + 用户就 OD-17/OD-19 拍板 | 全文 |
| 2026-10-08 | 新增 §2.2 数据模型「先预留后释放」决策 | 解决 PRD 内部冲突：F11（MVP）要求选兴趣标签，但 F6 标签体系被划为 MVP+ | §2.2 / §5.2 / §6.1 |
| 2026-10-08 | 核定实体表数 22 → 29 | `ARCHITECTURE.md` 第 623 行与第 818 行自相矛盾且基数错（写 23→29 与 23→30） | §2.3 |
| 2026-10-08 | 新增 §5.4 F2 校内认证端点 | 架构文档 §3 只覆盖 G1/G2/G3，遗漏了 MVP 的 F2 与 F11 端点定义 | §5.4 |
| 2026-10-08 | OD-18 裁定：不引入生成脚本 | 为一个文档级漂移增加构建依赖属过度设计 | §10.4 |
| 2026-10-08 | LCP 目标由 2.5s 收紧为 2.0s | 按设计师 §13.5 更严的预算；「不许再定一套」原则 | §9 AC-17 / §10.5 |
