# 发布前检查单（2026-11-01，1.13.11）

> 目的：把「上线后才发现漏了一个域」提前到设计和发布阶段。
> 每次新增/修改涉及用户、成员关系、状态或计数的功能，先过第一段四问。

## 一、功能设计四问

1. **注销/封禁/解散时，这张表怎么处理？**
   - 清理、移交给他人、下线不删、保留留痕，四选一。
   - 新增表或新增用户引用列后，更新 `UserReferenceInventoryTest` 白名单；否则测试失败。
2. **哪些冗余计数要变？**
   - 成员数、报名数、关注/粉丝数、加圈数、未读数。
   - 关系删除/插入必须通过 `MembershipCleanupSupport` 等单一入口，按实际影响行数决定是否改计数。
3. **哪些通知要发/不发？**
   - 接收人已注销时不再写通知；活动取消等批量通知要有去重条件。
4. **哪些公开读接口要隐藏？**
   - 父实体封禁/解散、操作者注销、成员被封禁时，详情/列表/报名/成员列表分别返回什么。
   - 在 `StateVisibilityMatrixTest` 补对应状态组合。

## 二、发布前门禁

| 门禁 | 命令/位置 | 通过标准 |
| --- | --- | --- |
| 后端全量测试 | `cd backend && mvn test` | 0 失败 0 错误 |
| 前端门禁 | `python scripts/run-all-gates.py` | 全部通过 |
| 生产构建 | `cd frontend && node scripts/build.mjs` | 生成新入口并记录 SHA256 |
| 更新日志 | `frontend/src/constants/changelog.js` | ≤400 行，版本号与 pom/package.json 一致 |
| 部署包校验 | `SHA256SUMS` + `verify-release-manifest.py` | 全部通过 |
| 迁移演练 | 备份库执行全部迁移 | 无报错；`sql/consistency_check.sql` 返回 0 |
| 一致性闸门 | `deploy/ops/check-consistency.sh` | `violations=0`，否则停止部署。部署包必须包含 `sql/consistency_check.sql` 与 `ops/check-consistency.sh`，`deploy.sh` 在迁移后、启动前调用 |
| 冒烟 | `curl -s https://yousheng8.cn/healthz` | 返回 UP |

## 三、部署与回滚

1. 宝塔上传 zip 到 `/opt/campus/incoming`，解压后执行 `bash deploy.sh`。
2. 脚本必须先做数据库备份、再停服落位、再执行迁移；失败由 EXIT trap 回滚 jar/web/VERSION。
3. 迁移顺序：历史结构/数据修复脚本 → `migration_2026-11-01_reconcile_counters.sql`（计数重算）→ `check-consistency.sh`；校验非 0 即失败并回滚应用文件，数据库用部署前备份恢复。
4. 部署后核对版本、前端入口、`/healthz`、`/api/user/me` 401、关键页面。

## 四、发布节奏

- 同一批修复合并为一个版本发布，避免一天多个版本。
- 紧急热修单独发版，但必须补跑全量测试与一致性校验。
- 每月至少一次把 `campus.data.drift` 指标与对账日志对照，确认没有新的漏路径。
