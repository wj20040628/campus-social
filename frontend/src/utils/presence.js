import { reactive } from 'vue'
import { status as statusApi } from '@/api/presence'
import { useUserStore } from '@/stores/user'

/**
 * 在线状态缓存（2026-11-13）。
 *
 * 头像是列表里最密集的元素，不能每个头像各发一次请求：
 * 组件只登记 userId，这里在 50ms 窗口内合并成一次批量查询；
 * 同一用户 30 秒内不重复请求，已登记用户每 30 秒整体刷新一次。
 * 未登录（游客）不请求，头像上没有点。
 */
const online = reactive(new Set())
const known = new Set()
const lastFetched = new Map()
let pending = new Set()
let flushTimer = null
let pollTimer = null
let inflight = false
/** visibilitychange 只挂一次；回前台时补一次强制刷新，见 refreshPresence */
let visBound = false

const TTL = 30 * 1000
const BATCH = 100

function hasToken() {
  try {
    return !!useUserStore().accessToken
  } catch {
    return false
  }
}

async function flush() {
  if (inflight || pending.size === 0 || !hasToken()) return
  const ids = [...pending].slice(0, BATCH)
  ids.forEach((id) => pending.delete(id))
  inflight = true
  try {
    const data = await statusApi(ids)
    const set = new Set((data?.online || []).map(Number))
    ids.forEach((id) => {
      online.delete(id)
      lastFetched.set(id, Date.now())
    })
    set.forEach((id) => online.add(id))
  } catch {
    // 失败不缓存，下次登记会重试
    ids.forEach((id) => lastFetched.delete(id))
  } finally {
    inflight = false
    if (pending.size) setTimeout(flush, 50)
  }
}

function startPoll() {
  if (!visBound) {
    visBound = true
    document.addEventListener('visibilitychange', onVisible)
  }
  if (pollTimer) return
  pollTimer = setInterval(() => {
    if (document.visibilityState !== 'visible' || !hasToken() || known.size === 0) return
    known.forEach((id) => pending.add(id))
    lastFetched.clear()
    flush()
  }, TTL)
}

function onVisible() {
  if (document.visibilityState === 'visible') refreshPresence()
}

/**
 * 强制重查已登记用户（绕开 30 秒 TTL）。
 *
 * <p>WS 刚连上 / 页面回到前台时调用。这两种时刻「谁在线」在服务端刚变化，
 * 而缓存里可能还留着变化前的「离线」——不强制重查的话，自己的绿点要等
 * 下一次 30 秒轮询才亮，刷新页面时还会先灰后绿。
 */
export function refreshPresence() {
  if (!hasToken() || known.size === 0) return
  known.forEach((id) => {
    lastFetched.delete(id)
    pending.add(id)
  })
  clearTimeout(flushTimer)
  flushTimer = setTimeout(flush, 50)
  startPoll()
}

export function ensurePresence(id) {
  const n = Number(id)
  if (!n || !hasToken()) return
  known.add(n)
  const last = lastFetched.get(n)
  if (last && Date.now() - last < TTL) return
  pending.add(n)
  clearTimeout(flushTimer)
  flushTimer = setTimeout(flush, 50)
  startPoll()
}

export function isOnline(id) {
  return online.has(Number(id))
}

/** 退出登录/注销时清空，避免下个账号看到上一个人的状态。 */
export function clearPresence() {
  online.clear()
  known.clear()
  lastFetched.clear()
  pending.clear()
  clearTimeout(flushTimer)
  flushTimer = null
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
  if (visBound) {
    document.removeEventListener('visibilitychange', onVisible)
    visBound = false
  }
}
