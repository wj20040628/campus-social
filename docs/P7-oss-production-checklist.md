# P7 · OSS 接入生产验证清单

上线前逐项打勾。OSS 接入的代码链路（P0~P5 后端 + P3 读链路 + P4 前端直传）已全部完成并通过本地构建；  
本清单只覆盖**生产环境落地与验证**，不分代码。

核心前提：**域名 `yousheng8.cn` 的 ICP 备案必须先过审**。直传依赖 OSS 把上传成功事件回调到  
`https://yousheng8.cn/upload/oss-callback`，备案中该回调不通，直传会被 OSS 判失败（前端会自动降级  
`POST /upload`，功能不崩，但没走直传）。

服务器：`ecs.e-c1m1.large`（2 核 2G，Ubuntu 22.04，华东1杭州），公网 `120.26.4.66`，私网 `192.168.25.254`。  
OSS bucket 与 ECS 同地域（杭州），后端走内网域名免流量费。

---

## 阶段 0 · 上线前置（硬门槛）

- [ ] **ICP 备案过审**：`yousheng8.cn` 备案状态 = 已通过。
- [ ] **DNS 解析**：`yousheng8.cn` A 记录指向 ECS 公网 `120.26.4.66`（备案过审后才能指向大陆服务器公网 IP）。
- [ ] **HTTPS 证书**：`yousheng8.cn` 已签有效证书，443 端口对外可达；后端 `/upload/oss-callback` 公网可 POST  
  （Nginx 反代或 Spring Boot 直开 SSL 均可，只要 OSS 能回调到 443）。
- [x] **CORS 白名单已配**：`app.cors.allowed-origins`（`CORS_ALLOWED_ORIGINS` env）填网站源站  
  （如 `https://yousheng8.cn`），不是通配符。

> 备案过审前不要做阶段 1~5 的"直传生效"动作；备案中可先把 `OSS_DIRECT_UPLOAD_ENABLED=false` 跑起来，  
> 走服务端 `POST /upload` 兜底（不依赖回调域名），验证读链路和兜底写链路。

---

## 阶段 1 · 密钥与配置注入（ECS 环境变量，绝不进仓库）

全部走 env，本地 `application-local.yml` 已 .gitignore，生产只用 env 覆盖。逐项确认注入且非空：

| env 变量                                                  | 作用                            | 备注                                            |
| ------------------------------------------------------- | ----------------------------- | --------------------------------------------- |
| `OSS_BUCKET`                                            | 桶名                            | 默认 `yousheng-uploads`                         |
| `OSS_REGION`                                            | 地域                            | 默认 `cn-hangzhou`                              |
| `OSS_ENDPOINT`                                          | 外网域名                          | 默认 `oss-cn-hangzhou.aliyuncs.com`             |
| `OSS_INTERNAL_ENDPOINT`                                 | 内网域名                          | 默认 `oss-cn-hangzhou-internal.aliyuncs.com`    |
| `OSS_ROLE_ARN`                                          | STS 扮演的 RAM 角色                | 默认已有 `acs:ram::...:role/oss-uploader-role`    |
| `OSS_STS_ENDPOINT`                                      | STS 接入点                       | 默认 `sts.cn-hangzhou.aliyuncs.com`             |
| `OSS_CALLBACK_URL`                                      | 回调地址                          | 默认 `https://yousheng8.cn/upload/oss-callback` |
| `OSS_CALLBACK_KEY`                                      | **回调验签 HMAC 密钥（32 hex）**      | 默认空，**必须填**；与签发端一致                            |
| `OSS_DIRECT_UPLOAD_ENABLED`                             | 前端直传开关                        | 默认 `false`，备案过审后设 `true`                      |
| `OSS_STS_ACCESS_KEY_ID` / `OSS_STS_ACCESS_KEY_SECRET`   | 换 STS 临时令牌的固定 AK（仅后端）         | 注意无 `Secret ` 前缀（P5 修过）                       |
| `OSS_READ_ACCESS_KEY_ID` / `OSS_READ_ACCESS_KEY_SECRET` | 生成读签名 URL 的只读 AK（仅 GetObject） | —                                             |

- [ ] 上述 env 全部注入，应用启动日志无 `IllegalArgumentException` / 空值告警。
- [ ] `OSS_CALLBACK_KEY` 与本地签发端、生产签发端**同一值**（32 hex）；漏填会导致 `GET /upload/sts` 正常但回调验签全 403。
- [ ] 重启后端，确认 `ossService.isConfigured()` = true（启动日志或探针可验）。

---

## 阶段 2 · OSS 控制台（infra）

- [x] **存储桶私有**：ACL = 私有，不开公共读。验证：直接 `curl https://{bucket}.{endpoint}/uploads/...` → `AccessDenied`。
- [x] **CORS 规则**：允许网站源站（如 `https://yousheng8.cn`）的 `PUT`/`POST`/`GET`/`HEAD`；  
  放行头（Allowed Header）含 `x-oss-callback`、`x-oss-callback-var`、`Authorization`、`Content-Type`、`x-oss-security-token`；  
  Expose Header 含 `ETag`。
- [x] **RAM 角色 `oss-uploader-role`**：可被 STS `AssumeRole`；其权限策略仅允许  
  `oss:PutObject` / `AbortMultipartUpload` / `ListParts`，且资源锁 `acs:oss:*:*:{bucket}/uploads/*`  
  （最小权限，无 `DeleteObject` / `GetObject` / 桶根 `ListObjects`）。
- [x] **回调解密端**：后端用内网域名 `oss-cn-hangzhou-internal.aliyuncs.com` 拉文件头做魔数校验，免流量费（配置已在 `internalEndpoint`）。
- [ ] **（可选）生命周期规则**：如热数据转低频 / 过期清理，按成本策略配。

---

## 阶段 3 · 安全项（原 1/2/3 + S1/S2/S3，上线前必过）

| 项                 | 实现点                                                                        | 验证方法（pass 标准）                                                       |
| ----------------- | -------------------------------------------------------------------------- | ------------------------------------------------------------------- |
| **原1 桶私有 + 仅签名读** | `MediaController` 302 到 `presignGetUrl`                                    | 直连对象 URL → 403；经 `/uploads/...` → 302 → 200                         |
| **原2 回调验签**       | `OSS_CALLBACK_KEY` 对 object 算 HMAC 塞 `x:sign`，`OssCallbackController` 重算比对 | 篡改 `x:sign` → 回调 403，对象成孤儿                                          |
| **原3 魔数校验**       | 回调拉头 16 字节 `FileMagic.matches`                                             | 上传 `.jpg` 实为可执行文件 → 回调 403                                          |
| **S1 防越权写**       | key 的 userId 由服务端生成；STS 策略锁 `uploads/{userId}/*`                           | 用 A 的 STS `PUT uploads/999/...` → ImplicitDeny 403                  |
| **S2 等回调 + 落库**   | 前端 `uploadToOss` 等 OSS 回调 200 才 resolve；后端 `insertOwnership`               | 直传成功后 `upload_file` 表有该 url 的 owner 行                               |
| **S3 STS 最小权限**   | `buildPolicy` 仅 Put/AbortMultipart/ListParts，前端只拿临时令牌不拿 AK                 | 用 STS 试 `DeleteObject` → 403；前端 Network 里只见 `SecurityToken` 不见固定 AK |

- [ ] 以上 6 项逐条实测通过（直接传一个真图 + 一个伪装文件各验一次）。
- [ ] 确认 `WebMvcConfig` 已把 `/upload/oss-callback` 从鉴权拦截器放行（OSS 回调不带用户 token，否则全 401）。

---

## 阶段 4 · 读链路（P3）验证

- [ ] `GET /uploads/{userId}/{day}/{uuid}.ext` → 302 到签名 URL → 浏览器 200 加载图片/视频。
- [ ] 缩略图：`GET /uploads/{key}?t=1` → 302 带 `x-oss-process=image/resize,w_480` → 返回更小图（降本⑦）。
- [ ] 老格式 2 段 URL（`/uploads/{day}/{uuid}`，无 userId）本环境已无数据（测试数据已删），无需兼容；  
  若以后出现，读链路按 `resolveStoredThumb` 仍能映射到 OSS key（OSS key 始终 3 段）。

---

## 阶段 5 · 写链路端到端（直传 + 兜底）

- [ ] `GET /upload/config` 返回 `ossEnabled=true`（=`isConfigured() && isDirectUploadEnabled()`），前端据此选直传。
- [ ] **3 个上传入口**真机走通（浏览器直传 OSS → 回调落库 → 展示）：
  - [ ] 发帖（Publish）：图片 + 视频各一
  - [ ] 头像（SettingsProfile）
  - [ ] 圈子发帖（CircleComposer）：图片 + 视频各一
- [ ] **回调成功判定**：上传后查 `upload_file` 表有对应行（owner = 当前用户）。
- [ ] **降级验证**：临时把 `OSS_DIRECT_UPLOAD_ENABLED=false` 重启，3 入口改走 `POST /upload` 服务端兜底仍成功（验证兜底链路存活）。
- [ ] **大文件分片**：传一个接近 `max-video-bytes`(10MB) 的视频，确认 ali-oss 内部分片 + STS 权限够（ListParts/AbortMultipartUpload 已授权）。

---

## 阶段 6 · 费用监控与告警

OSS 费用三大头：**存储容量**、**外网流出流量（下行，最大头）**、**请求次数（PUT/GET）**。

- [ ] 开通费用告警（资源包或按量，超阈值短信/站内信）。
- [ ] 评估买 **OSS 资源包**（存储包 + 下行流量包）降本，校园场景日均上传量小，按量也便宜。
- [ ] **降本⑦（待做，非阻塞）**：前端列表/头像小图改走 `?t=1`（x-oss-process 实时缩略），少下行原图流量。  
  当前前端列表图直接用原图 URL，上线前建议补上。
- [ ] 后端 `presignTtlSeconds=3600`：签名 URL 1h 有效，够用；勿设过长。

---

## 阶段 7 · 孤儿清理（S6）

- [x] **早期探针孤儿先清**：真实 bucket 里 `uploads/1/20261018/8c315b4e-...png`（P5 调试遗留，186B）手动删。
- [ ] **常态化清理任务**：定期 `ListObjects` 桶内 `uploads/` 前缀，与 `upload_file` 表比对，  
  删除「无归属记录且超过 N 天（建议 7 天）」的对象（失败回调 403 留下的孤儿）。  
  \- 注意：STS 临时令牌**无 DeleteObject** 权限，清理需单独的清理 AK / 控制台手动，或给清理角色最小 `DeleteObject` 权限。
- [x] 清理任务先在测试桶演练，确认只删真孤儿、不误删在用对象。

---

## 阶段 8 · 回滚 / 熔断开关

- [ ] **直传熔断**：`OSS_DIRECT_UPLOAD_ENABLED=false` 重启 → 前端全量降级 `POST /upload`（服务端 STS 写 OSS）。  
  仍依赖 OSS（读+写都走 OSS），但绕开浏览器直传 + CORS + 回调域名，排直传类故障时用。
- [ ] **OSS 整体不可用极端态**：若 OSS 挂，读（302）和写（POST /upload 兜底）都失败。此时临时关图片上传功能  
  或切外部图床；属极端，正常可用性高，不预建。
- [ ] 开关改动只需改 env + 重启，零代码回滚。

---

## 阶段 9 · 上线后监控（1~2 天观察期）

- [ ] 后端日志无 `OssCallbackController` 大量 403（验签/魔数失败 = 有人在传伪装文件，正常会有少量）。
- [ ] 无 `AssumeRole` 失败（STS AK 过期 / 角色权限被改）。
- [ ] OSS 下行流量曲线正常，无异常暴涨（防被盗链：桶私有 + 仅签名 URL，已基本挡住）。
- [ ] 前端监控：直传成功率（对比 `GET /upload/sts` 调用量 与 成功上传数），若直传失败率突增先查 CORS / 回调域名可达性。

---

## 已知遗留 / 非阻塞项

- 前端列表/头像缩略图未用 `?t=1`（降本⑦），上线前建议补。
- 2 个测试账号（`user.id=4` WJ、`id=5`）的 `avatar_url` 仍指着已删的 `/uploads/...`，上线前重传头像即可。
- `ali-oss` SDK 已按需动态加载（仅真走直传时下载），备案中直传关闭时完全不下发，不影响首屏体积。
- `upload_file` 表 DDL 已建（`backend/sql/upload_file.sql`），生产 ECS 的库需先跑此文件。
