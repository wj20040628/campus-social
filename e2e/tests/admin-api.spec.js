import { test, expect } from '@playwright/test'
import {
  API_BASE, auth, cleanup, createDb, createRedis, dbValue, seedAdmin, seedUser,
} from './helpers.js'

/**
 * 真实 HTTP 的管理端 / 账号链路：举报处置、禁言、注销。
 *
 * 这些动作在浏览器里需要管理员面板与邮箱验证码，CI 里走真实接口更稳定；
 * 后端、MySQL、Redis 全是真的，不 mock。
 */
test.describe.configure({ mode: 'serial' })

let db
let redis
const userIds = []
const postIds = []
const emails = []

test.beforeAll(async () => {
  db = createDb()
  redis = createRedis()
  await redis.ping()
})

test.afterAll(async () => {
  await cleanup(db, redis, { userIds, postIds, emails })
  await db.end()
  await redis.quit()
})

async function expectOk(res, label) {
  const text = await res.text()
  expect(res.ok(), `${label} HTTP ${res.status()}: ${text}`).toBeTruthy()
  return text ? JSON.parse(text) : null
}

test('举报处置：管理员下架帖子并落库', async ({ request }) => {
  const suffix = Date.now()
  const author = await seedUser(db, { nickname: 'E2E 举报作者' })
  const reporter = await seedUser(db, { nickname: 'E2E 举报人' })
  const admin = await seedAdmin(db)
  userIds.push(author.id, reporter.id, admin.id)

  const postBody = await expectOk(await request.post(`${API_BASE}/post`, {
    headers: auth(author),
    data: { content: `E2E-REPORT-TARGET-${suffix}` },
  }), '发帖')
  const postId = postBody.data.postId
  postIds.push(postId)

  const reportBody = await expectOk(await request.post(`${API_BASE}/report`, {
    headers: auth(reporter),
    data: {
      targetType: 2,
      targetId: postId,
      reasonType: 2,
      reasonText: 'E2E 自动化举报：广告',
    },
  }), '举报')
  const reportId = reportBody.data.reportId
  expect(reportId).toBeTruthy()

  await expectOk(await request.post(`${API_BASE}/admin/reports/${reportId}/handle`, {
    headers: auth(admin),
    data: { action: 'takedown', handleNote: 'E2E 自动化：仅下架' },
  }), '处置举报')

  expect(await dbValue(db, 'SELECT status FROM report WHERE id=?', [reportId])).toBe(1)
  expect(await dbValue(db, 'SELECT status FROM post WHERE id=?', [postId])).toBe(2)
  expect(await dbValue(db, 'SELECT handle_action FROM report WHERE id=?', [reportId])).toBe('takedown')
})

test('禁言：管理员禁言后 /user/me 有截止时间，发帖返回 1075', async ({ request }) => {
  const target = await seedUser(db, { nickname: 'E2E 被禁言用户' })
  const admin = await seedAdmin(db)
  userIds.push(target.id, admin.id)

  await expectOk(await request.post(`${API_BASE}/admin/users/${target.id}/mute`, {
    headers: auth(admin),
    data: { muteDays: 7, reason: 'E2E 自动化：连续刷屏' },
  }), '禁言')

  const meBody = await expectOk(await request.get(`${API_BASE}/user/me`, {
    headers: auth(target),
  }), '读取 /user/me')
  expect(meBody.data.muteUntil).toBeTruthy()
  expect(await dbValue(db,
    'SELECT COUNT(*) FROM user WHERE id=? AND mute_until > NOW()', [target.id])).toBe(1)
  expect(await dbValue(db,
    "SELECT COUNT(*) FROM audit_log WHERE action='account_mute' AND target_type='user' AND target_id=?",
    [target.id])).toBe(1)

  const denied = await request.post(`${API_BASE}/post`, {
    headers: auth(target),
    data: { content: `E2E-MUTED-${Date.now()}` },
  })
  expect(denied.status()).toBe(403)
  expect((await denied.json()).code).toBe(1075)
})

test('注销：验证码确认后停用并清空邮箱/密码', async ({ request }) => {
  const account = await seedUser(db, { nickname: 'E2E 注销用户' })
  userIds.push(account.id)
  emails.push(account.username)

  await redis.set(`code:cancel:${account.username}`, '123456', 'EX', 300)
  await expectOk(await request.post(`${API_BASE}/user/cancel`, {
    headers: auth(account),
    data: { code: '123456' },
  }), '注销')

  expect(await dbValue(db, 'SELECT status FROM user WHERE id=?', [account.id])).toBe(2)
  expect(await dbValue(db, 'SELECT email FROM user WHERE id=?', [account.id])).toBeNull()
  expect(await dbValue(db, 'SELECT password_hash FROM user WHERE id=?', [account.id]))
    .toBe('__NO_PASSWORD__')
  expect(await dbValue(db, 'SELECT nickname FROM user WHERE id=?', [account.id]))
    .toBe('已注销用户')
  expect(await dbValue(db, 'SELECT username FROM user WHERE id=?', [account.id]))
    .toBe(`deleted_${account.id}`)
  expect(await redis.get(`code:cancel:${account.username}`)).toBeNull()
})
