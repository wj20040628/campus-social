# AI 机审启用指南（2026-11-08）

> 结论先给：**不是只填一个 API key 就能用**。最少要同时配 3 项：
> `LLM_ENABLED=true`、`LLM_API_KEY`、`LLM_MODEL`；如果不用 Claude/OpenAI 官方端点，
> 还要配 `LLM_BASE_URL`。配完必须重启后端，再在「内容审核」页确认状态条变成「已启用」。

## 1. 它审什么、不审什么

- **只审命中一级敏感词的内容**：发帖/编辑时 `containsReplace(content)` 为真才入队并触发机审；
  完全正常的内容不会调用大模型，不产生费用。
- 机审结论：
  - `approve`：帖子自动通过并进 Feed；
  - `reject`：帖子自动下架并通知作者；
  - `manual`：保持待审，留给人工。
- **任何失败都会转人工**：没有 key、超时、4xx/5xx、返回不是合法 JSON、解析失败，
  都不会让帖子丢失或卡死；原因写进 `review_task.ai_reason`。
- 发送给模型的内容只有：帖子正文 + 媒体 JSON（图片/视频地址），**不含**用户 ID、
  昵称、学号、邮箱、手机号。这一点已写进《隐私政策》第 5 条。

## 2. 生产配置（宝塔 / systemd）

编辑后端服务文件（`/etc/systemd/system/campus.service`，路径以实际为准），
在 `[Service]` 段的环境变量区加入：

```ini
# 必填
Environment=LLM_ENABLED=true
Environment=LLM_API_KEY=服务商控制台生成的Key
Environment=LLM_MODEL=服务商控制台里的模型名

# 选择一：Claude 官方端点
Environment=LLM_PROVIDER=claude
# LLM_BASE_URL 留空即可，默认 https://api.anthropic.com

# 选择二：OpenAI 官方端点
# Environment=LLM_PROVIDER=openai
# LLM_BASE_URL 留空即可，默认 https://api.openai.com/v1

# 选择三：OpenAI 兼容端点（DeepSeek / 通义 / 豆包等，以服务商文档为准）
# Environment=LLM_PROVIDER=openai
# Environment=LLM_BASE_URL=https://api.deepseek.com/v1
# Environment=LLM_MODEL=deepseek-chat

# 可选：单次调用超时，默认 30 秒
# Environment=LLM_TIMEOUT_SEC=30
```

保存后：

```bash
systemctl daemon-reload
systemctl restart campus
systemctl status campus --no-pager
```

> 密钥只放环境变量，不进仓库、不进部署包、不要贴到聊天里。
> `.e2e-env/` 与部署脚本都不需要知道 key。

## 3. 如何确认真的启用了

1. 用管理员登录网站，进入「内容审核」；
2. 页面顶部状态条应显示：
   `AI 机审：已启用：<provider> / <model>；只审核命中敏感词的内容，调用失败会自动转人工`；
3. 如果显示「未启用：在服务器环境变量设置 LLM_ENABLED=true 并重启后端」，
   说明 `LLM_ENABLED` 没生效；显示「缺密钥」则是 `LLM_API_KEY` 为空；
4. 用一个普通账号（非受信用户）发一条命中一级敏感词的帖子：
   - 帖子先显示「审核中」；
   - 几秒后若机审成功，自动变成「已通过」或「已下架」；
   - 若机审返回 manual/失败，仍留在待审队列，任务里会带 AI 理由。

状态条数据来自 `GET /admin/review/ai-status`，该接口只回配置状态，不回传密钥。

## 4. 成本与阈值

- 同一份「正文 + 媒体」的 SHA-256 结果缓存 24 小时，重复内容不重复扣费；
- 失败/转人工的缓存 1 小时，便于短时间自愈；
- 只有命中敏感词的内容才会调用，正常发帖不产生费用；
- 如果只想先观察、不想自动处置，把 `LLM_PROVIDER` 配好后先喂少量内容，
  机审 `manual` 结论不会自动通过/下架，等于「AI 只给建议、人审拍板」。

## 5. 常见问题

| 现象 | 原因 | 处理 |
| --- | --- | --- |
| 状态条显示未启用 | `LLM_ENABLED` 不是 `true` | 改 systemd 环境变量并 `daemon-reload` + 重启 |
| 状态条显示缺密钥 | `LLM_API_KEY` 为空 | 填 key，注意不要带引号/空格 |
| 任务理由「LLM 调用失败: 401」 | key 错误或 provider 不匹配 | Claude 用 `x-api-key`，OpenAI 兼容用 Bearer；核对 provider |
| 任务理由「LLM 未返回合法 JSON」 | 模型没按 JSON 输出 | 换更强的模型，或保持 manual 人审 |
| 一直 manual 但状态条已启用 | 模型判不准 / 内容确实模糊 | 正常兜底；人工审核台仍可处置 |
