import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import bcrypt from 'bcryptjs'
import jwt from 'jsonwebtoken'
import mysql from 'mysql2/promise'
import Redis from 'ioredis'

const HERE = path.dirname(fileURLToPath(import.meta.url))

export const API_BASE = process.env.E2E_API_BASE_URL || 'http://127.0.0.1:8080'

export function unique(prefix = 'e2e') {
  return `${prefix}_${Date.now().toString(36)}_${Math.random().toString(36).slice(2, 8)}`
}

function accessSecret() {
  if (process.env.E2E_JWT_ACCESS_SECRET) return process.env.E2E_JWT_ACCESS_SECRET
  if (process.env.JWT_ACCESS_SECRET) return process.env.JWT_ACCESS_SECRET
  const yml = path.resolve(HERE, '../../backend/src/main/resources/application-local.yml')
  const match = fs.readFileSync(yml, 'utf8').match(/access-secret:\s*(\S+)/)
  if (!match) throw new Error(`无法从 ${yml} 读取 access-secret，请设置 E2E_JWT_ACCESS_SECRET`)
  return match[1]
}

export function token(user) {
  return jwt.sign(
    { sub: String(user.id), username: user.username, typ: 'access' },
    accessSecret(),
    { algorithm: 'HS512', expiresIn: '1h' },
  )
}

export function auth(userOrToken) {
  const value = typeof userOrToken === 'string' ? userOrToken : token(userOrToken)
  return { Authorization: `Bearer ${value}` }
}

export function createDb() {
  return mysql.createPool({
    host: process.env.E2E_MYSQL_HOST || process.env.MYSQL_HOST || '127.0.0.1',
    port: Number(process.env.E2E_MYSQL_PORT || process.env.MYSQL_PORT || 3306),
    user: process.env.E2E_MYSQL_USER || process.env.MYSQL_USER || 'root',
    password: dbPassword(),
    database: process.env.E2E_MYSQL_DATABASE || 'campus_social_v2',
    charset: 'utf8mb4',
    connectionLimit: 3,
  })
}

/** CI 走环境变量；本机从 gitignored 的 application-local.yml 读，避免把库密码写进仓库。 */
function dbPassword() {
  if (process.env.E2E_MYSQL_PASSWORD != null) return process.env.E2E_MYSQL_PASSWORD
  if (process.env.MYSQL_PASSWORD != null) return process.env.MYSQL_PASSWORD
  const yml = path.resolve(HERE, '../../backend/src/main/resources/application-local.yml')
  try {
    const match = fs.readFileSync(yml, 'utf8').match(/datasource:[\s\S]*?password:\s*(\S+)/)
    if (match) return match[1]
  } catch {
    // 文件不存在时交给连接失败报错，提示已足够明确
  }
  return ''
}

export function createRedis() {
  return new Redis({
    host: process.env.E2E_REDIS_HOST || process.env.REDIS_HOST || '127.0.0.1',
    port: Number(process.env.E2E_REDIS_PORT || process.env.REDIS_PORT || 6379),
    db: Number(process.env.E2E_REDIS_DB || 0),
    maxRetriesPerRequest: 2,
  })
}

export async function seedUser(db, {
  nickname = 'E2E 用户',
  role = 0,
  password = '',
  username,
} = {}) {
  const login = username || `${unique('user')}@campus.test`
  const hash = password ? bcrypt.hashSync(password, 10) : '__NO_PASSWORD__'
  const [result] = await db.execute(
    `INSERT INTO user (username, password_hash, nickname, status, role, school_id,
       verify_status, stranger_msg_enabled, is_deleted, email, create_time)
     VALUES (?,?,?,0,?,1,1,1,0,?,DATE_SUB(NOW(), INTERVAL 2 DAY))`,
    [login, hash, nickname, role, login],
  )
  return { id: result.insertId, username: login, nickname, password }
}

export async function seedAdmin(db, nickname = 'E2E 管理员') {
  return seedUser(db, { nickname, role: 1 })
}

export async function dbValue(db, sql, params = []) {
  const [rows] = await db.execute(sql, params)
  if (!rows.length) return null
  return rows[0][Object.keys(rows[0])[0]]
}

export async function cleanup(db, redis, { userIds = [], postIds = [], emails = [] } = {}) {
  for (const uid of userIds) {
    await db.query('DELETE FROM review_task WHERE post_id IN (SELECT id FROM post WHERE author_id=?)', [uid])
    await db.query('DELETE FROM `like` WHERE user_id=?', [uid])
    await db.query('DELETE FROM post_favorite WHERE user_id=?', [uid])
    await db.query('DELETE FROM comment WHERE user_id=?', [uid])
    await db.query('DELETE FROM feed_item WHERE fan_id=? OR author_id=?', [uid, uid])
    await db.query('DELETE FROM notify WHERE user_id=?', [uid])
    await db.query('DELETE FROM report WHERE reporter_id=? OR (target_type=1 AND target_id=?)', [uid, uid])
    await db.query('DELETE FROM audit_log WHERE user_id=? OR (target_type="user" AND target_id=?)', [uid, uid])
    await db.query('DELETE FROM relation WHERE from_user_id=? OR to_user_id=?', [uid, uid])
    await db.query('DELETE FROM `like` WHERE target_type=1 AND target_id IN (SELECT id FROM post WHERE author_id=?)', [uid])
    await db.query('DELETE FROM post_favorite WHERE post_id IN (SELECT id FROM post WHERE author_id=?)', [uid])
    await db.query('DELETE FROM comment WHERE post_id IN (SELECT id FROM post WHERE author_id=?)', [uid])
    await db.query('DELETE FROM feed_item WHERE post_id IN (SELECT id FROM post WHERE author_id=?)', [uid])
    await db.query('DELETE FROM post_topic WHERE post_id IN (SELECT id FROM post WHERE author_id=?)', [uid])
    await db.query('DELETE FROM report WHERE target_type=2 AND target_id IN (SELECT id FROM post WHERE author_id=?)', [uid])
    await db.query('DELETE FROM post WHERE author_id=?', [uid])
    await db.query('DELETE FROM user WHERE id=?', [uid])
    const keys = await redis.keys(`rl:w:*:u:${uid}`)
    if (keys.length) await redis.del(...keys)
    await redis.del(`refresh:${uid}`, `user:status:${uid}`)
  }
  for (const pid of postIds) {
    await db.query('DELETE FROM review_task WHERE post_id=?', [pid])
    await db.query('DELETE FROM `like` WHERE target_type=1 AND target_id=?', [pid])
    await db.query('DELETE FROM post_favorite WHERE post_id=?', [pid])
    await db.query('DELETE FROM comment WHERE post_id=?', [pid])
    await db.query('DELETE FROM feed_item WHERE post_id=?', [pid])
    await db.query('DELETE FROM post_topic WHERE post_id=?', [pid])
    await db.query('DELETE FROM report WHERE target_type=2 AND target_id=?', [pid])
    await db.query('DELETE FROM post WHERE id=?', [pid])
  }
  for (const email of emails) {
    await redis.del(`code:cancel:${email}`, `code:fail:cancel:${email}`)
  }
}
