import { test, expect } from '@playwright/test'
import { createDb, createRedis, cleanup, dbValue, seedUser } from './helpers.js'

/**
 * 浏览器主链路：登录 → 发帖 → 评论。
 *
 * 不 mock 后端；用户来自真实 MySQL，密码走真实 BCrypt 校验。
 * 管理员类流程在 admin-api.spec.js 用真实 HTTP 接口覆盖。
 */
test.describe.configure({ mode: 'serial' })

let db
let redis
let user
let postId = null

test.beforeAll(async () => {
  db = createDb()
  redis = createRedis()
  await redis.ping()
  user = await seedUser(db, {
    nickname: 'E2E UI 用户',
    password: 'E2ePass123!',
  })
})

test.afterAll(async () => {
  await cleanup(db, redis, {
    userIds: user ? [user.id] : [],
    postIds: postId ? [postId] : [],
    emails: user ? [user.username] : [],
  })
  await db.end()
  await redis.quit()
})

test('登录 → 发帖 → 评论（真实前端 + 后端）', async ({ page }) => {
  const serverErrors = []
  page.on('response', (res) => {
    if (res.url().includes('/api/') && res.status() >= 500) {
      serverErrors.push(`${res.status()} ${res.url()}`)
    }
  })

  await page.goto('/login/form')
  await page.locator('input[placeholder="输入学号,或绑定的邮箱"]').fill(user.username)
  await page.locator('input[type="password"]').fill(user.password)
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await page.waitForFunction(() => !!localStorage.getItem('access_token'))
  await expect(page).toHaveURL(/\/$/)

  const postText = `E2E-UI-${Date.now()}`
  await page.goto('/publish')
  await page.locator('#pub-content').fill(postText)
  await page.getByRole('button', { name: '发布', exact: true }).click()
  await expect(page).toHaveURL(/\/$/, { timeout: 20_000 })
  await expect(page.getByText(postText).first()).toBeVisible({ timeout: 20_000 })

  postId = await dbValue(
    db,
    'SELECT id FROM post WHERE author_id=? AND content=? ORDER BY id DESC LIMIT 1',
    [user.id, postText],
  )
  expect(postId).toBeTruthy()

  await page.goto(`/post/${postId}`)
  const commentText = `E2E-COMMENT-${Date.now()}`
  await page.getByPlaceholder(/写下你的评论/).fill(commentText)
  await page.getByRole('button', { name: '发送评论' }).click()
  await expect(page.getByText(commentText).first()).toBeVisible({ timeout: 20_000 })

  expect(serverErrors, `服务端 5xx: ${serverErrors.join(' | ')}`).toEqual([])
})
