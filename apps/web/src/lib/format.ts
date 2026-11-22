import type {
  AssistSessionState,
  MaterialState,
  ProcessReviewState,
  ReminderState,
  ReviewState,
  Role,
  SourceState,
  TaskStatus,
  TaskStepStatus
} from './types'

// 状态展示元信息（文案/颜色）。状态值本身严格来自后端，这里只做展示映射。
export const TASK_STATUS_META: Record<TaskStatus, { label: string; color: string }> = {
  DRAFT: { label: '草稿', color: 'default' },
  PENDING_CONFIRMATION: { label: '待确认', color: 'gold' },
  ACTIVE: { label: '进行中', color: 'blue' },
  IN_PROGRESS: { label: '执行中', color: 'cyan' },
  BLOCKED: { label: '受阻', color: 'red' },
  PENDING_REVIEW: { label: '待复核', color: 'orange' },
  COMPLETED: { label: '已完成', color: 'green' },
  CANCELLED: { label: '已取消', color: 'default' },
  DISMISSED: { label: '已忽略', color: 'default' }
}

export const STEP_STATUS_META: Record<TaskStepStatus, { label: string; color: string }> = {
  TODO: { label: '待办', color: 'default' },
  IN_PROGRESS: { label: '执行中', color: 'blue' },
  BLOCKED: { label: '受阻', color: 'red' },
  PENDING_REVIEW: { label: '待复核', color: 'orange' },
  DONE: { label: '已完成', color: 'green' },
  CANCELLED: { label: '已取消', color: 'default' }
}

export const REVIEW_STATE_META: Record<ReviewState, { label: string; color: string }> = {
  PENDING: { label: '待复核', color: 'gold' },
  APPROVED: { label: '已通过', color: 'green' },
  REJECTED: { label: '已驳回', color: 'red' }
}

export const ROLE_META: Record<Role, { label: string; color: string }> = {
  OWNER: { label: '所有者', color: 'purple' },
  ADMIN: { label: '管理员', color: 'magenta' },
  MEMBER: { label: '成员', color: 'blue' },
  VIEWER: { label: '观察者', color: 'default' }
}

export function formatDateTime(iso: string | null): string {
  if (!iso) return '—'
  try {
    return new Date(iso).toLocaleString('zh-CN', { hour12: false })
  } catch {
    return iso
  }
}

export function formatDate(iso: string | null): string {
  if (!iso) return '—'
  try {
    return new Date(iso).toLocaleDateString('zh-CN')
  } catch {
    return iso
  }
}

// 截止时间是否已逾期（仅用于展示提醒，不做业务判定）。
export function isOverdue(dueAt: string | null, status: TaskStatus): boolean {
  if (!dueAt) return false
  if (status === 'COMPLETED' || status === 'CANCELLED' || status === 'DISMISSED') return false
  return new Date(dueAt).getTime() < Date.now()
}

// 来源可信状态：颜色只表达风险，文案始终同时出现（指导书 9.4）。
export const SOURCE_STATE_META: Record<SourceState, { label: string; color: string }> = {
  VERIFIED_OFFICIAL: { label: '已核验官方来源', color: 'green' },
  USER_PROVIDED: { label: '用户提供', color: 'blue' },
  COMMUNITY_UNVERIFIED: { label: '社区未核验', color: 'gold' },
  EXPIRED: { label: '来源已失效', color: 'red' },
  FETCH_FAILED: { label: '抓取失败', color: 'red' }
}

export const PROCESS_REVIEW_META: Record<ProcessReviewState, { label: string; color: string }> = {
  DRAFT: { label: '草稿', color: 'default' },
  IN_REVIEW: { label: '待复核', color: 'gold' },
  VERIFIED: { label: '已核验', color: 'green' },
  PERSONAL_UNVERIFIED: { label: '个人未核验', color: 'orange' },
  EXPIRED: { label: '已失效', color: 'red' }
}

export const MATERIAL_STATE_META: Record<MaterialState, { label: string; color: string }> = {
  MISSING: { label: '未准备', color: 'red' },
  PROVIDED: { label: '已提供', color: 'blue' },
  VERIFIED: { label: '已核验', color: 'green' },
  NOT_REQUIRED: { label: '不需要', color: 'default' }
}

export const REMINDER_STATE_META: Record<ReminderState, { label: string; color: string }> = {
  SCHEDULED: { label: '已排期', color: 'blue' },
  SENT: { label: '已发送', color: 'green' },
  CANCELLED: { label: '已取消', color: 'default' },
  FAILED: { label: '发送失败', color: 'red' }
}

export const ASSIST_STATE_META: Record<AssistSessionState, { label: string; color: string }> = {
  IDLE: { label: '待邀请', color: 'default' },
  CONSENT_PENDING: { label: '待对方接受', color: 'gold' },
  ACTIVE: { label: '进行中', color: 'blue' },
  PAUSED: { label: '已暂停', color: 'orange' },
  COMPLETED: { label: '已完成', color: 'green' },
  STOPPED: { label: '已停止', color: 'default' },
  EXPIRED: { label: '已过期', color: 'red' }
}

/** 把 ISO 时间显示为“x 分钟前”，用于候选/事件流；解析失败回退原文。 */
export function relativeTime(iso: string | null): string {
  if (!iso) return '—'
  const t = new Date(iso).getTime()
  if (Number.isNaN(t)) return iso
  const diff = Date.now() - t
  if (diff < 60_000) return '刚刚'
  if (diff < 3_600_000) return `${Math.floor(diff / 60_000)} 分钟前`
  if (diff < 86_400_000) return `${Math.floor(diff / 3_600_000)} 小时前`
  return formatDateTime(iso)
}
