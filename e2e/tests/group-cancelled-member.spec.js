import { test, expect } from '@playwright/test'
import {
  API_BASE, auth, createDb, createRedis, seedUser,
} from './helpers.js'

/**
 * 回归：群里有人注销后，进群加载历史消息不应再弹「用户不存在」。
 *
 * 根因是群历史会逐个拉发送者名片，/user/{id} 对 status=2 返回 404，
 * 全局拦截器把 404 弹成 toast；昵称兜底也被降级成「用户<id>」。
 */
test.describe.configure({ mode: 'serial' })

let db
let redis
let owner
let member
let groupId = null
const messageText = `E2E-注销成员消息-${Date.now()}`

async function post(url, user, body) {
  const res = await fetch(`${API_BASE}${url}`, {
    method: 'POST',
    headers: { ...auth(user), 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
  return res.json()
}

test.beforeAll(async () => {
  db = createDb()
  redis = createRedis()
  await redis.ping()
  owner = await seedUser(db, { nickname: 'E2E 群主', password: 'E2ePass123!' })
  member = await seedUser(db, { nickname: 'E2E 注销成员' })

  // 建群允许拉「我关注的人」；注销后关系行仍保留，不影响历史消息
  await db.execute(
    'INSERT INTO relation (from_user_id, to_user_id, type, is_deleted) VALUES (?,?,1,0)',
    [owner.id, member.id],
  )
  const created = await post('/group', owner, {
    name: `E2E注销群-${Date.now()}`,
    avatarUrl: null,
    memberIds: [member.id],
  })
  expect(created.code, JSON.stringify(created)).toBe(200)
  groupId = created.data.groupId

  const sent = await post(`/group/${groupId}/message`, member, {
    content: messageText,
    replyToId: null,
  })
  expect(sent.code, JSON.stringify(sent)).toBe(200)

  // 与 UserMapper.cancelAccount 同口径，保留用户行供历史消息引用
  await db.execute(
    `UPDATE user SET status=2, username=CONCAT('deleted_', id), email=NULL,
       student_no=NULL, nickname='已注销用户', bio=NULL, avatar_url=NULL, cover_url=NULL,
       mute_until=NULL, grade=NULL, grade_id=NULL, dept=NULL, dept_id=NULL, major_id=NULL,
       campus=NULL, verify_status=0, password_hash='__NO_PASSWORD__'
     WHERE id=?`,
    [member.id],
  )
  await redis.del(`user:status:${member.id}`)
})

test.afterAll(async () => {
  if (groupId) {
    await db.query('DELETE FROM group_message WHERE group_id=?', [groupId])
    await db.query('DELETE FROM group_read WHERE group_id=?', [groupId])
    await db.query('DELETE FROM group_member WHERE group_id=?', [groupId])
    await db.query('DELETE FROM notify WHERE target_id=?', [groupId])
    await db.query('DELETE FROM `group` WHERE id=?', [groupId])
  }
  for (const u of [owner, member].filter(Boolean)) {
    await db.query('DELETE FROM relation WHERE from_user_id=? OR to_user_id=?', [u.id, u.id])
    await db.query('DELETE FROM user WHERE id=?', [u.id])
    await redis.del(`user:status:${u.id}`, `refresh:${u.id}`)
  }
  await db.end()
  await redis.quit()
})

test('已注销成员的历史消息显示已注销，且不再弹用户不存在', async ({ page }) => {
  await page.goto('/login/form')
  await page.locator('input[placeholder="输入学号,或绑定的邮箱"]').fill(owner.username)
  await page.locator('input[type="password"]').fill(owner.password)
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await page.waitForFunction(() => !!localStorage.getItem('access_token'))

  await page.goto(`/chat/t/group/${groupId}`)
  await expect(page.getByText(messageText).first()).toBeVisible({ timeout: 20_000 })
  // 必须落在消息气泡的发送者名上；成员名单里也可能出现「已注销用户」，
  // 只查全页文本会把兜底失败误判成通过。
  const record = page.locator('.blk', { hasText: messageText })
  await expect(record.locator('.blk__name')).toHaveText('已注销用户', { timeout: 20_000 })

  // 等过 toast 的显示窗口，确认没有任何「用户不存在」
  await page.waitForTimeout(1500)
  await expect(page.locator('.el-message').filter({ hasText: '用户不存在' })).toHaveCount(0)
})
