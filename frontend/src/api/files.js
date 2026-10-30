import request from '@/utils/request'
import { useUserStore } from '@/stores/user'

/**
 * 文件库（2026-10-27）。
 *
 * 下载走浏览器直接导航：后端 302 到短签名 URL，比 XHR 取 blob
 * 更省内存，也不受 OSS 跨域限制。分享页游客同样可用。
 */
const API_BASE = (import.meta.env.VITE_API_BASE_URL || '/api').replace(/\/$/, '')

export function list(params = {}) {
  const query = {
    tab: params.tab || 'all',
    sort: params.sort || 'latest',
    page: params.page || 1,
    size: params.size || 20,
  }
  if (params.sub) query.sub = params.sub
  if (params.category) query.category = params.category
  if (params.keyword) query.keyword = params.keyword
  return request.get('/files', { params: query })
}

export function quota() {
  return request.get('/files/quota')
}

export function upload(file, { title, category, description }) {
  const fd = new FormData()
  fd.append('file', file)
  fd.append('title', title)
  fd.append('category', category)
  if (description) fd.append('description', description)
  return request.post('/files', fd, {
    headers: { 'Content-Type': 'multipart/form-data' },
    timeout: 120000,
    silent: true,
  })
}

export function detail(id) {
  return request.get(`/files/${id}`)
}

export function remove(id) {
  return request.delete(`/files/${id}`)
}

export function like(id) {
  return request.post(`/files/${id}/like`)
}

export function unlike(id) {
  return request.delete(`/files/${id}/like`)
}

export function favorite(id) {
  return request.post(`/files/${id}/favorite`)
}

export function unfavorite(id) {
  return request.delete(`/files/${id}/favorite`)
}

export function publicDetail(id) {
  return request.get(`/public/files/${id}`)
}

export function downloadUrl(id) {
  return `${API_BASE}/files/${id}/download`
}

/** 在线预览地址（PDF/TXT/MD）。未过审文件需带 token fetch，不能用直链 */
export function previewUrl(id) {
  return `${API_BASE}/files/${id}/preview`
}

async function fetchBlob(url) {
  const token = useUserStore().accessToken
  const resp = await fetch(url, {
    headers: token ? { Authorization: `Bearer ${token}` } : {},
  })
  if (!resp.ok) {
    let msg = '文件读取失败'
    try {
      const data = await resp.json()
      msg = data?.message || msg
    } catch {
      // 非 JSON 错误体保持默认文案
    }
    throw new Error(msg)
  }
  return resp.blob()
}

/**
 * 带登录态的下载。
 *
 * 已过审文件用 downloadUrl 直链即可（游客也能下、走 OSS 直出）；
 * 但上传者预览自己的待审文件时，直链不带 Authorization，后端认不出作者，
 * 会按「未过审对他人不可见」回 404。这种情况必须 fetch 成 blob 再存。
 */
export function downloadBlob(id) {
  return fetchBlob(downloadUrl(id))
}

/** 预览取字节：PDF 转 blob URL 给 iframe，TXT/MD 读文本 */
export function previewBlob(id) {
  return fetchBlob(previewUrl(id))
}

// ---------------------------------------------------------------- 管理端

export function adminList(params = {}) {
  const query = { page: params.page || 1, size: params.size || 20 }
  if (params.status !== undefined && params.status !== null) query.status = params.status
  if (params.keyword) query.keyword = params.keyword
  return request.get('/admin/files', { params: query })
}

export function adminReview(id, action, reason) {
  return request.post(`/admin/files/${id}/review`, { action, reason }, { silent: true })
}

export function adminTakedown(id, reason) {
  return request.post(`/admin/files/${id}/takedown`, { reason }, { silent: true })
}

/**
 * 管理员下载待审/任意状态文件。
 *
 * 普通下载端点是 302 到 OSS，浏览器直连不带 Authorization；
 * 管理端要能下载「待审」文件，所以走带 token 的 fetch 取 blob。
 */
export async function adminDownloadBlob(id) {
  const token = useUserStore().accessToken
  const resp = await fetch(`${API_BASE}/admin/files/${id}/download`, {
    headers: token ? { Authorization: `Bearer ${token}` } : {},
  })
  if (!resp.ok) {
    let msg = '文件下载失败'
    try {
      const data = await resp.json()
      msg = data?.message || msg
    } catch {
      // 非 JSON 错误体保持默认文案
    }
    throw new Error(msg)
  }
  return resp.blob()
}

export default {
  list, quota, upload, detail, remove, like, unlike, favorite, unfavorite,
  publicDetail, downloadUrl, previewUrl, downloadBlob, previewBlob,
  adminList, adminReview, adminTakedown, adminDownloadBlob,
}
