import axios, { AxiosError, type InternalAxiosRequestConfig } from 'axios'
import { notifyAuthCleared } from './authEvents'
import { clearTokens, getAccessToken, getRefreshToken, setTokens } from './authTokens'
import type {
  AccountDeletionView,
  ApiResponse,
  ApiErrorBody,
  Asset,
  AssistEvent,
  AssistSession,
  AssistTurnCredentials,
  AuditEntry,
  Candidate,
  CandidateStatus,
  Comment,
  Completion,
  CursorPage,
  ExportJob,
  Material,
  MaterialState,
  MeResponse,
  Member,
  MemoryContext,
  MemoryHit,
  JobStatus,
  OcrResult,
  Page,
  PendingCompletion,
  ProcessTemplate,
  ProcessVersion,
  Reminder,
  ReminderChannel,
  ServerEvent,
  Session,
  SourceItem,
  SourceRecheckResult,
  SourceType,
  Step,
  Task,
  TaskSourceLink,
  TaskStatus,
  TimePrecision,
  Workspace,
  WorkspaceDetail,
  WorkspaceType,
  Role,
  DevicePlatform
} from './types'

// baseURL 解析：
// - 浏览器（含 Tauri dev，经 Vite 同源代理）：默认 /api/v1；
// - Tauri 生产构建无 Vite 代理，直连本机后端 8080；可被 VITE_API_BASE_URL 覆盖。
const inTauri =
  typeof window !== 'undefined' && ('__TAURI_INTERNALS__' in window || '__TAURI__' in window)
const BASE_URL =
  (import.meta.env.VITE_API_BASE_URL as string | undefined) ||
  (inTauri ? 'http://localhost:8080/api/v1' : '/api/v1')

// axios 实例：dev 经 vite 代理同源转发到 8080，不触发 CORS。
export const api = axios.create({
  baseURL: BASE_URL,
  timeout: 15_000,
  withCredentials: true
})

// 请求拦截：注入 Bearer access token。
api.interceptors.request.use((config) => {
  const token = getAccessToken()
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

// 401 自动 refresh 重试（单次，防循环）。
let refreshing: Promise<string> | null = null

function performRefresh(): Promise<string> {
  const refreshToken = getRefreshToken()
  if (!refreshToken) {
    clearTokens()
    return Promise.reject(new Error('NO_REFRESH_TOKEN'))
  }
  return api
    .post<ApiResponse<Session>>('/auth/refresh', { refreshToken })
    .then(({ data }) => {
      setTokens({ accessToken: data.data.accessToken, refreshToken: data.data.refreshToken })
      return data.data.accessToken
    })
    .catch((err) => {
      clearTokens()
      notifyAuthCleared()
      throw err
    })
}

api.interceptors.response.use(
  (resp) => resp,
  async (error: AxiosError) => {
    const original = error.config as (InternalAxiosRequestConfig & { _retried?: boolean }) | undefined
    const status = error.response?.status
    const url = original?.url ?? ''
    const isRefreshCall = url.includes('/auth/refresh') || url.includes('/auth/login')
    if (status === 401 && original && !original._retried && !isRefreshCall) {
      original._retried = true
      try {
        if (!refreshing) {
          refreshing = performRefresh().finally(() => {
            refreshing = null
          })
        }
        const newToken = await refreshing
        original.headers.Authorization = `Bearer ${newToken}`
        return api(original)
      } catch {
        return Promise.reject(error)
      }
    }
    return Promise.reject(error)
  }
)

// 解包统一信封 ApiResponse<T> → T。
async function unwrap<T>(p: Promise<{ data: ApiResponse<T> }>): Promise<T> {
  const { data } = await p
  return data.data
}

// 从 axios 错误中提取后端错误码 / 文案，供 UI 做确定性提示。
export function getErrorCode(error: unknown): string | undefined {
  const e = error as AxiosError<{ error?: ApiErrorBody }>
  return e.response?.data?.error?.code
}

export function getErrorMessage(error: unknown, fallback = '请求失败，请稍后重试'): string {
  const e = error as AxiosError<{ error?: ApiErrorBody }>
  const body = e.response?.data?.error
  if (body?.message) return body.message
  if (e.code === 'ECONNABORTED') return '请求超时，请检查网络'
  if (!e.response) return '无法连接后端，请确认 API 已启动'
  return fallback
}

// ---------------- Auth ----------------
export interface RegisterPayload {
  email: string
  username: string
  password: string
  displayName?: string
  timezone?: string
  locale?: string
  deviceName: string
  devicePlatform: DevicePlatform
}
export interface LoginPayload {
  login: string
  password: string
  deviceName: string
  devicePlatform: DevicePlatform
}

export const authApi = {
  register: (req: RegisterPayload) => unwrap<Session>(api.post<ApiResponse<Session>>('/auth/register', req)),
  login: (req: LoginPayload) => unwrap<Session>(api.post<ApiResponse<Session>>('/auth/login', req)),
  me: () => unwrap<MeResponse>(api.get<ApiResponse<MeResponse>>('/me')),
  logout: () => unwrap<void>(api.post<ApiResponse<void>>('/auth/logout'))
}

// 账户注销（指导书 13.2/15.5）：请求需密码重认证，进入冷却期；冷却期内可取消。
export const accountApi = {
  requestDeletion: (password: string, reason?: string) =>
    unwrap<AccountDeletionView>(
      api.delete<ApiResponse<AccountDeletionView>>('/me', { data: reason ? { password, reason } : { password } })
    ),
  cancelDeletion: () =>
    unwrap<AccountDeletionView>(api.post<ApiResponse<AccountDeletionView>>('/me/deletion/cancel'))
}

// ---------------- Workspace ----------------
export interface CreateWorkspacePayload {
  name: string
  type?: WorkspaceType
}
export interface InviteMemberPayload {
  email: string
  role: Role
}

export const workspaceApi = {
  list: () => unwrap<Workspace[]>(api.get<ApiResponse<Workspace[]>>('/workspaces')),
  create: (req: CreateWorkspacePayload) => unwrap<Workspace>(api.post<ApiResponse<Workspace>>('/workspaces', req)),
  detail: (id: string) => unwrap<WorkspaceDetail>(api.get<ApiResponse<WorkspaceDetail>>(`/workspaces/${id}`)),
  members: (id: string) => unwrap<Member[]>(api.get<ApiResponse<Member[]>>(`/workspaces/${id}/members`)),
  invite: (id: string, req: InviteMemberPayload) => unwrap<Member>(api.post<ApiResponse<Member>>(`/workspaces/${id}/members`, req)),
  changeRole: (id: string, userId: string, role: Role) =>
    unwrap<Member>(api.patch<ApiResponse<Member>>(`/workspaces/${id}/members/${userId}`, { role })),
  remove: (id: string, userId: string) => unwrap<void>(api.delete<ApiResponse<void>>(`/workspaces/${id}/members/${userId}`)),
  accept: (id: string) => unwrap<Member>(api.post<ApiResponse<Member>>(`/workspaces/${id}/invitations/accept`))
}

// ---------------- Task ----------------
export interface CreateTaskPayload {
  workspaceId: string
  title: string
  status?: TaskStatus
  assigneeId?: string
  dueAt?: string
  dueDate?: string
  dueTimezone?: string
  priority?: number
  description?: string
}
export interface UpdateTaskPayload {
  baseVersion: number
  title?: string
  assigneeId?: string | null
  dueAt?: string | null
  dueDate?: string | null
  priority?: number | null
  description?: string | null
}
export interface TransitionPayload {
  status: TaskStatus
  baseVersion: number
  reason?: string
}

export const taskApi = {
  list: (workspaceId: string, status?: TaskStatus, page = 0, size = 50) =>
    unwrap<Page<Task>>(
      api.get<ApiResponse<Page<Task>>>('/tasks', { params: { workspaceId, status, page, size } })
    ),
  create: (req: CreateTaskPayload) => unwrap<Task>(api.post<ApiResponse<Task>>('/tasks', req)),
  get: (id: string, workspaceId: string) =>
    unwrap<Task>(api.get<ApiResponse<Task>>(`/tasks/${id}`, { params: { workspaceId } })),
  update: (id: string, workspaceId: string, req: UpdateTaskPayload) =>
    unwrap<Task>(api.patch<ApiResponse<Task>>(`/tasks/${id}`, req, { params: { workspaceId } })),
  transition: (id: string, workspaceId: string, req: TransitionPayload) =>
    unwrap<Task>(api.post<ApiResponse<Task>>(`/tasks/${id}/transitions`, req, { params: { workspaceId } })),
  remove: (id: string, workspaceId: string, baseVersion: number) =>
    unwrap<void>(api.delete<ApiResponse<void>>(`/tasks/${id}`, { params: { workspaceId, baseVersion } }))
}

// ---------------- Step ----------------
export interface CreateStepPayload {
  title: string
  instruction?: string
  position?: number
  assigneeId?: string
}

export const stepApi = {
  list: (taskId: string, workspaceId: string) =>
    unwrap<Step[]>(api.get<ApiResponse<Step[]>>(`/tasks/${taskId}/steps`, { params: { workspaceId } })),
  create: (taskId: string, workspaceId: string, req: CreateStepPayload) =>
    unwrap<Step>(api.post<ApiResponse<Step>>(`/tasks/${taskId}/steps`, req, { params: { workspaceId } })),
  remove: (taskId: string, workspaceId: string, stepId: string) =>
    unwrap<void>(api.delete<ApiResponse<void>>(`/tasks/${taskId}/steps/${stepId}`, { params: { workspaceId } }))
}

// ---------------- Completion ----------------
export interface SubmitCompletionPayload {
  statement?: string
  assetIds?: string[]
}

export const completionApi = {
  list: (stepId: string, workspaceId: string) =>
    unwrap<Completion[]>(api.get<ApiResponse<Completion[]>>(`/steps/${stepId}/completions`, { params: { workspaceId } })),
  submit: (stepId: string, workspaceId: string, req: SubmitCompletionPayload) =>
    unwrap<Completion>(api.post<ApiResponse<Completion>>(`/steps/${stepId}/completions`, req, { params: { workspaceId } }))
}

// ---------------- Asset ----------------
export interface InitUploadResponse {
  sessionId: string
  upload: { method: string; url: string; headers: Record<string, string>; proxied: boolean }
  expiresInSeconds: number | null
  existing: Asset | null
}

async function sha256Hex(file: File): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-256', await file.arrayBuffer())
  return Array.from(new Uint8Array(digest))
    .map((b) => b.toString(16).padStart(2, '0'))
    .join('')
}

export const assetApi = {
  get: (assetId: string, workspaceId: string) =>
    unwrap<Asset>(api.get<ApiResponse<Asset>>(`/assets/${assetId}`, { params: { workspaceId } })),
  downloadUrl: (assetId: string, workspaceId: string) =>
    unwrap<string>(api.get<ApiResponse<string>>(`/assets/${assetId}/download-url`, { params: { workspaceId } })),
  /** 初始化 → PUT 字节 → complete；同 sha256 命中已有资产时直接复用（指导书 5.6 去重）。 */
  async upload(workspaceId: string, file: File): Promise<Asset> {
    const sha256 = await sha256Hex(file)
    const mime = file.type || 'application/octet-stream'
    const init = await unwrap<InitUploadResponse>(
      api.post<ApiResponse<InitUploadResponse>>('/assets/uploads', {
        workspaceId,
        sha256,
        mime,
        sizeBytes: file.size,
        fileName: file.name
      })
    )
    if (init.existing) return init.existing
    await api.put(`/assets/uploads/${init.sessionId}/content`, file, {
      headers: { 'Content-Type': mime },
      transformRequest: [(data: unknown) => data]
    })
    return unwrap<Asset>(
      api.post<ApiResponse<Asset>>(`/assets/uploads/${init.sessionId}/complete`, {
        sha256,
        sizeBytes: file.size
      })
    )
  }
}

// ---------------- OCR / Job ----------------
export const ocrApi = {
  /** 入队云端 OCR；必须显式授权（cloudAuthorized=true），前端只在用户确认后调用。 */
  enqueue: (assetId: string, workspaceId: string, model?: string) =>
    unwrap<JobStatus>(
      api.post<ApiResponse<JobStatus>>(`/assets/${assetId}/ocr`, {
        workspaceId,
        cloudAuthorized: true,
        model
      })
    ),
  /** 读取已生成的 OCR 文本；不存在时后端返回 RESOURCE_NOT_FOUND，由调用方区分“未识别”。 */
  result: (assetId: string, workspaceId: string, model?: string) =>
    unwrap<OcrResult>(
      api.get<ApiResponse<OcrResult>>(`/assets/${assetId}/ocr`, { params: { workspaceId, model } })
    )
}

export const jobApi = {
  get: (jobId: string, workspaceId: string) =>
    unwrap<JobStatus>(api.get<ApiResponse<JobStatus>>(`/jobs/${jobId}`, { params: { workspaceId } })),
  /** 只有死信任务可人工重试；与 OCR 入队的固定幂等键配合，避免重复缓存同一失败任务。 */
  retry: (jobId: string, workspaceId: string) =>
    unwrap<JobStatus>(
      api.post<ApiResponse<JobStatus>>(`/jobs/${jobId}/retry`, null, { params: { workspaceId } })
    )
}

// ---------------- Source ----------------
export interface ShareSourcePayload {
  workspaceId: string
  sourceType: SourceType
  text: string
  sourceApp?: string
  sourceKey?: string
  title?: string
  linkUrl?: string
  receivedAt?: string
  timezone?: string
  assetId?: string
  localOnly?: boolean
}

export const sourceApi = {
  list: (workspaceId: string, page = 0, size = 20) =>
    unwrap<Page<SourceItem>>(api.get<ApiResponse<Page<SourceItem>>>('/sources', { params: { workspaceId, page, size } })),
  get: (sourceId: string, workspaceId: string) =>
    unwrap<SourceItem>(api.get<ApiResponse<SourceItem>>(`/sources/${sourceId}`, { params: { workspaceId } })),
  share: (req: ShareSourcePayload) => unwrap<SourceItem>(api.post<ApiResponse<SourceItem>>('/sources/share', req)),
  recheck: (sourceId: string, workspaceId: string) =>
    unwrap<SourceRecheckResult>(
      api.post<ApiResponse<SourceRecheckResult>>(`/sources/${sourceId}/recheck`, null, { params: { workspaceId } })
    ),
  remove: (sourceId: string, workspaceId: string, baseVersion: number) =>
    unwrap<void>(
      api.delete<ApiResponse<void>>(`/sources/${sourceId}`, { params: { workspaceId, baseVersion } })
    )
}

// ---------------- Inbox ----------------
export interface InboxConfirmPayload {
  baseVersion: number
  title?: string
  dueAt?: string | null
  dueDate?: string | null
  timePrecision?: TimePrecision
  dueTimezone?: string
  assigneeId?: string
  dueDateConfirmed?: boolean
  notes?: string
  reminders?: Array<{ triggerAt: string; channel: ReminderChannel }>
  steps?: string[]
  materials?: string[]
}

export interface InboxConfirmResult {
  taskId: string
  candidateId: string
  taskVersion: number
  stepCount: number
  reminderCount: number
  evidenceRefIds: string[]
  createdAt: string
}

export const inboxApi = {
  list: (workspaceId: string, status: CandidateStatus | undefined = 'PENDING', cursor?: string, limit = 20) =>
    unwrap<CursorPage<Candidate>>(
      api.get<ApiResponse<CursorPage<Candidate>>>('/inbox/candidates', {
        params: { workspaceId, status, cursor, limit }
      })
    ),
  get: (candidateId: string, workspaceId: string) =>
    unwrap<Candidate>(
      api.get<ApiResponse<Candidate>>(`/inbox/candidates/${candidateId}`, { params: { workspaceId } })
    ),
  confirm: (candidateId: string, req: InboxConfirmPayload, idempotencyKey?: string) =>
    unwrap<InboxConfirmResult>(
      api.post<ApiResponse<InboxConfirmResult>>(`/inbox/candidates/${candidateId}/confirm`, req, {
        headers: idempotencyKey ? { 'Idempotency-Key': idempotencyKey } : undefined
      })
    ),
  dismiss: (candidateId: string, baseVersion: number, reason?: string) =>
    unwrap<Candidate>(
      api.post<ApiResponse<Candidate>>(`/inbox/candidates/${candidateId}/dismiss`, { baseVersion, reason })
    )
}

// ---------------- Memory ----------------
export const memoryApi = {
  search: (workspaceId: string, q: string, type?: string, cursor?: string, limit = 20) =>
    unwrap<CursorPage<MemoryHit>>(
      api.get<ApiResponse<CursorPage<MemoryHit>>>('/memory/search', { params: { workspaceId, q, type, cursor, limit } })
    ),
  context: (documentId: string, workspaceId: string) =>
    unwrap<MemoryContext>(
      api.get<ApiResponse<MemoryContext>>(`/memory/${documentId}/context`, { params: { workspaceId } })
    )
}

// ---------------- Process ----------------
export interface ProcessStepInput {
  instruction: string
  required?: boolean
  officialEntryUrl?: string
  sourceRefIds?: string[]
}

export const processApi = {
  list: (workspaceId: string, cursor?: string, limit = 20) =>
    unwrap<CursorPage<ProcessTemplate>>(
      api.get<ApiResponse<CursorPage<ProcessTemplate>>>('/processes', { params: { workspaceId, cursor, limit } })
    ),
  get: (templateId: string, workspaceId: string) =>
    unwrap<ProcessTemplate>(
      api.get<ApiResponse<ProcessTemplate>>(`/processes/${templateId}`, { params: { workspaceId } })
    ),
  create: (req: { workspaceId: string; title: string; regionCode?: string; scopeJson?: string }) =>
    unwrap<ProcessTemplate>(api.post<ApiResponse<ProcessTemplate>>('/processes', req)),
  createVersion: (templateId: string, steps: ProcessStepInput[]) =>
    unwrap<ProcessVersion>(
      api.post<ApiResponse<ProcessVersion>>(`/processes/${templateId}/versions`, { steps })
    ),
  replaceSteps: (versionId: string, steps: ProcessStepInput[]) =>
    unwrap<ProcessVersion>(api.put<ApiResponse<ProcessVersion>>(`/process-versions/${versionId}/steps`, { steps })),
  publish: (versionId: string, req: { allowPersonalUnverified?: boolean; reviewerNote?: string }) =>
    unwrap<ProcessVersion>(api.post<ApiResponse<ProcessVersion>>(`/process-versions/${versionId}/publish`, req)),
  instantiate: (
    versionId: string,
    req: { workspaceId: string; title?: string; assigneeId?: string; dueAt?: string; dueTimezone?: string }
  ) => unwrap<Task>(api.post<ApiResponse<Task>>(`/process-versions/${versionId}/instantiate`, req))
}

// ---------------- Assist ----------------
export const assistApi = {
  mine: () => unwrap<AssistSession[]>(api.get<ApiResponse<AssistSession[]>>('/assist/sessions')),
  create: (req: {
    workspaceId: string
    taskStepId?: string
    helperId?: string
    mode?: string
    scopes?: string[]
    expiresMinutes?: number
  }) => unwrap<AssistSession>(api.post<ApiResponse<AssistSession>>('/assist/sessions', req)),
  accept: (sessionId: string) =>
    unwrap<AssistSession>(api.post<ApiResponse<AssistSession>>(`/assist/sessions/${sessionId}/accept`)),
  events: (sessionId: string, afterSeq = 0) =>
    unwrap<AssistEvent[]>(
      api.get<ApiResponse<AssistEvent[]>>(`/assist/sessions/${sessionId}/events`, { params: { afterSeq } })
    ),
  /** 只读信令：服务端白名单外的 CLICK/SWIPE/INPUT 等会直接拒绝并审计。 */
  signal: (sessionId: string, eventType: string, payload: Record<string, unknown> = {}) =>
    unwrap<AssistEvent>(
      api.post<ApiResponse<AssistEvent>>(`/assist/sessions/${sessionId}/signal`, { eventType, payload })
    ),
  stop: (sessionId: string, reason?: string) =>
    unwrap<AssistSession>(api.post<ApiResponse<AssistSession>>(`/assist/sessions/${sessionId}/stop`, { reason })),
  turnCredentials: (sessionId: string) =>
    unwrap<AssistTurnCredentials>(
      api.post<ApiResponse<AssistTurnCredentials>>(`/assist/sessions/${sessionId}/turn-credentials`)
    )
}

// ---------------- Collaboration ----------------
export const commentApi = {
  list: (taskId: string, workspaceId: string) =>
    unwrap<Comment[]>(api.get<ApiResponse<Comment[]>>(`/tasks/${taskId}/comments`, { params: { workspaceId } })),
  create: (taskId: string, workspaceId: string, body: string) =>
    unwrap<Comment>(
      api.post<ApiResponse<Comment>>(`/tasks/${taskId}/comments`, { body }, { params: { workspaceId } })
    ),
  remove: (taskId: string, commentId: string, workspaceId: string) =>
    unwrap<void>(
      api.delete<ApiResponse<void>>(`/tasks/${taskId}/comments/${commentId}`, { params: { workspaceId } })
    )
}

export const reminderApi = {
  list: (taskId: string, workspaceId: string) =>
    unwrap<Reminder[]>(api.get<ApiResponse<Reminder[]>>(`/tasks/${taskId}/reminders`, { params: { workspaceId } })),
  replace: (taskId: string, workspaceId: string, reminders: Array<{ triggerAt: string; channel: ReminderChannel }>) =>
    unwrap<Reminder[]>(
      api.put<ApiResponse<Reminder[]>>(`/tasks/${taskId}/reminders`, { reminders }, { params: { workspaceId } })
    )
}

export const materialApi = {
  list: (taskId: string, workspaceId: string) =>
    unwrap<Material[]>(api.get<ApiResponse<Material[]>>(`/tasks/${taskId}/materials`, { params: { workspaceId } })),
  create: (
    taskId: string,
    workspaceId: string,
    req: {
      name: string
      state?: MaterialState
      requiredConditionJson?: string
      formatRuleJson?: string
      evidenceRefId?: string
      assetId?: string
    }
  ) => unwrap<Material>(api.post<ApiResponse<Material>>(`/tasks/${taskId}/materials`, req, { params: { workspaceId } })),
  update: (
    taskId: string,
    materialId: string,
    workspaceId: string,
    req: {
      baseVersion: number
      name?: string
      state?: MaterialState
      requiredConditionJson?: string
      formatRuleJson?: string
      evidenceRefId?: string
      assetId?: string
    }
  ) =>
    unwrap<Material>(
      api.patch<ApiResponse<Material>>(`/tasks/${taskId}/materials/${materialId}`, req, { params: { workspaceId } })
    ),
  remove: (taskId: string, materialId: string, workspaceId: string) =>
    unwrap<void>(
      api.delete<ApiResponse<void>>(`/tasks/${taskId}/materials/${materialId}`, { params: { workspaceId } })
    )
}

export const taskSourceApi = {
  list: (taskId: string, workspaceId: string) =>
    unwrap<TaskSourceLink[]>(
      api.get<ApiResponse<TaskSourceLink[]>>(`/tasks/${taskId}/sources`, { params: { workspaceId } })
    )
}

export const reviewApi = {
  pending: (workspaceId: string) =>
    unwrap<PendingCompletion[]>(
      api.get<ApiResponse<PendingCompletion[]>>(`/workspaces/${workspaceId}/completions/pending`)
    ),
  review: (completionId: string, approve: boolean, reason?: string) =>
    unwrap<Completion>(
      api.post<ApiResponse<Completion>>(`/completions/${completionId}/review`, { approve, reason })
    )
}

// ---------------- Audit / Export / Sync ----------------
export const auditApi = {
  list: (workspaceId: string, opts: { objectType?: string; objectId?: string; cursor?: string; limit?: number } = {}) =>
    unwrap<CursorPage<AuditEntry>>(
      api.get<ApiResponse<CursorPage<AuditEntry>>>('/audit', {
        params: { workspaceId, ...opts, limit: opts.limit ?? 20 }
      })
    )
}

export const exportApi = {
  mine: () => unwrap<ExportJob[]>(api.get<ApiResponse<ExportJob[]>>('/exports')),
  // 高敏导出需密码重新认证（指导书 13.2 / 15.3）；服务端异步生成，返回 PENDING。
  create: (workspaceId: string, password: string, exportType = 'WORKSPACE_JSON') =>
    unwrap<ExportJob>(api.post<ApiResponse<ExportJob>>('/exports', { workspaceId, exportType, password })),
  async download(exportId: string): Promise<Blob> {
    const resp = await api.get(`/exports/${exportId}/download`, { responseType: 'blob' })
    return resp.data as Blob
  }
}

export const syncApi = {
  events: (workspaceId: string, afterSeq = 0, limit = 100) =>
    unwrap<ServerEvent[]>(
      api.get<ApiResponse<ServerEvent[]>>('/sync/events', { params: { workspaceId, afterSeq, limit } })
    )
}
