/**
 * 消息分组(2026-10-20 群聊改版)
 *
 * 把线性的消息数组折成「日期分隔 / 系统行 / 分组」三种块交给模板渲染。
 * 分组是渲染层的事,不碰数据:后端返回的还是原始消息流。
 */
import { computed } from 'vue'

/** 同一人连发超过这个间隔就另起一组 */
export const GROUP_GAP_MS = 5 * 60 * 1000

const WEEK = ['周日', '周一', '周二', '周三', '周四', '周五', '周六']
const pad2 = (n) => String(n).padStart(2, '0')

/** 跨天判定按「年月日」而不是时间差:消息跨月/跨年时差值法会算错 */
export function dayKey(ts) {
  const d = new Date(ts)
  return `${d.getFullYear()}-${d.getMonth() + 1}-${d.getDate()}`
}

export function fmtClock(ts) {
  const d = new Date(ts)
  return `${pad2(d.getHours())}:${pad2(d.getMinutes())}`
}

/** 日期分隔:今天/昨天/一周内报星期,更早报「10月11日 周日」 */
export function fmtDay(ts) {
  const d = new Date(ts)
  const today = new Date()
  today.setHours(0, 0, 0, 0)
  const that = new Date(d)
  that.setHours(0, 0, 0, 0)
  const diff = Math.round((today - that) / 86400000)
  if (diff === 0) return '今天'
  if (diff === 1) return '昨天'
  if (diff > 1 && diff < 7) return WEEK[d.getDay()]
  return `${d.getMonth() + 1}月${d.getDate()}日 ${WEEK[d.getDay()]}`
}

/**
 * 三条规则:
 *  1) 跨天先插日期分隔,并强制断开当前组(一组不跨天);
 *  2) 撤回不当消息渲染 —— 它是系统事件,居中小字,且会打断分组;
 *  3) 同一发送者(我 = 'me',别人按 senderId)且间隔 ≤5 分钟才并组。
 *
 * 「已读」只标在自己最后一条分组上:群聊的 status=1 表示「至少一人读到」,
 * 没有逐人名单,所以只显示「已读」两个字,不编造人数。
 */
export function buildMessageBlocks(messages, kind) {
  const out = []
  let cur = null
  let lastDay = ''

  for (const m of messages || []) {
    const ts = m.sendTs || Date.now()
    const dk = dayKey(ts)

    if (dk !== lastDay) {
      out.push({ t: 'day', key: 'day-' + dk, text: fmtDay(ts) })
      lastDay = dk
      cur = null
    }

    if (m.recalled) {
      const who = m.fromMe ? '你' : (m.senderNickname || '对方')
      out.push({ t: 'sys', key: 'sys-' + m.id, text: `${who}撤回了一条消息` })
      cur = null
      continue
    }

    const sender = m.fromMe ? 'me' : String(m.senderId ?? m.senderNickname ?? 'x')
    if (!cur || cur.sender !== sender || ts - cur.lastTs > GROUP_GAP_MS) {
      cur = {
        t: 'grp',
        key: 'grp-' + m.id,
        fromMe: !!m.fromMe,
        sender,
        // 只有「群聊里别人发的」才显示昵称与头像;单聊和本人都不需要
        name: kind === 'group' && !m.fromMe ? (m.senderNickname || '群成员') : '',
        initial: (m.senderNickname || '群').slice(0, 1),
        time: fmtClock(ts),
        msgs: [],
        lastTs: ts,
        read: false,
      }
      out.push(cur)
    }
    cur.msgs.push(m)
    cur.lastTs = ts
  }

  for (let i = out.length - 1; i >= 0; i--) {
    const b = out[i]
    if (b.t !== 'grp' || !b.fromMe) continue
    b.read = b.msgs.some((m) => m.status === 1)
    break
  }
  return out
}

/** 传 ChatThread 的 props 即可,内部跟随 messages / kind 变化重算 */
export function useMessageBlocks(props) {
  return computed(() => buildMessageBlocks(props.messages, props.kind))
}
