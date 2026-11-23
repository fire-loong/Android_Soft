// 与后端 domain 枚举 / DTO 严格对齐（指导书 13：前端不得自行定义状态/日期格式）。
// 来源：services/api/src/main/java/com/actionloop/api/domain 与 web/dto。

export type TaskStatus =
  | 'DRAFT'
  | 'PENDING_CONFIRMATION'
  | 'ACTIVE'
  | 'IN_PROGRESS'
  | 'BLOCKED'
  | 'PENDING_REVIEW'
  | 'COMPLETED'
  | 'CANCELLED'
  | 'DISMISSED'

export type TaskStepStatus = 'TODO' | 'IN_PROGRESS' | 'BLOCKED' | 'PENDING_REVIEW' | 'DONE' | 'CANCELLED'

export type ReviewState = 'PENDING' | 'APPROVED' | 'REJECTED'

export type Role = 'OWNER' | 'ADMIN' | 'MEMBER' | 'VIEWER'

export type WorkspaceType = 'PERSONAL' | 'TEAM'

export type DevicePlatform = 'ANDROID' | 'WEB' | 'WINDOWS'

export type TimePrecision = 'DATE' | 'MINUTE'

export type UserStatus = 'ACTIVE' | 'LOCKED'

export interface User {
  id: string
  email: string
  username: string
  displayName: string
  status: UserStatus
  timezone: string | null
  locale: string | null
  createdAt: string
}

export interface Session {
  accessToken: string
  refreshToken: string
  tokenType: string
  expiresIn: number
  user: User
}

export interface Workspace {
  id: string
  name: string
  type: WorkspaceType
  role: Role
  joinedAt: string
}

/** GET /me 返回：当前用户 + 其空间列表（后端 MeResponse）。 */
export interface MeResponse {
  user: User
  workspaces: Workspace[]
}

export interface WorkspaceDetail {
  id: string
  name: string
  type: WorkspaceType
  ownerId: string
  myRole: Role
  createdAt: string
}

export interface Member {
  userId: string
  username: string
  displayName: string
  role: Role
  status: string
  joinedAt: string
}

export interface Invite {
  workspaceId: string
  workspaceName: string
  inviterName: string | null
  invitedAt: string
  expiresAt: string
}

export interface Task {
  id: string
  workspaceId: string
  title: string
  status: TaskStatus
  ownerId: string
  assigneeId: string | null
  dueAt: string | null
  dueDate: string | null
  dueTimezone: string | null
  timePrecision: TimePrecision | null
  priority: number | null
  description: string | null
  derivation: string | null
  confidence: number | null
  modelVersion: string | null
  promptVersion: string | null
  version: number
  createdAt: string
  updatedAt: string
  createdBy: string
  allowedTransitions: TaskStatus[]
}

export interface Step {
  id: string
  taskId: string
  processStepId: string | null
  title: string
  instruction: string | null
  position: number | null
  assigneeId: string | null
  status: TaskStepStatus
  completionRuleJson: string | null
  version: number
  createdAt: string
  updatedAt: string
}

export interface Completion {
  id: string
  taskStepId: string
  submitterId: string
  statement: string | null
  reviewState: ReviewState
  reviewerId: string | null
  reviewReason: string | null
  submittedAt: string
  reviewedAt: string | null
  assetIds: string[]
}

export interface Page<T> {
  content: T[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}

// 统一响应信封（shared ApiResponse）。
export interface ApiResponse<T> {
  data: T
  requestId: string
}

export interface ApiErrorBody {
  code: string
  message: string
  requestId: string
  details?: Record<string, unknown>
}

export interface ApiErrorEnvelope {
  error: ApiErrorBody
}

// ---------------- 统一游标分页（指导书 13.1：items + nextCursor + hasMore） ----------------
export interface CursorPage<T> {
  items: T[]
  nextCursor: string | null
  hasMore: boolean
}

export type SourceType = 'NOTIFICATION' | 'SHARE' | 'SCREENSHOT' | 'FILE' | 'LINK' | 'USER_STATEMENT' | 'OFFICIAL'
export type PrivacyLevel = 'S0' | 'S1' | 'S2' | 'S3'
export type SourceState =
  | 'VERIFIED_OFFICIAL'
  | 'USER_PROVIDED'
  | 'COMMUNITY_UNVERIFIED'
  | 'EXPIRED'
  | 'FETCH_FAILED'
export type CandidateStatus = 'PENDING' | 'CONFIRMED' | 'DISMISSED'
export type Derivation = 'AI' | 'RULE' | 'USER' | 'OFFICIAL' | 'USER_CONFIRMED_AI'
export type ReminderChannel = 'IN_APP' | 'NOTIFICATION' | 'HIGH_PRIORITY'
export type ReminderState = 'SCHEDULED' | 'SENT' | 'CANCELLED' | 'FAILED'
export type MaterialState = 'MISSING' | 'PROVIDED' | 'VERIFIED' | 'NOT_REQUIRED'
export type ProcessReviewState = 'DRAFT' | 'IN_REVIEW' | 'VERIFIED' | 'PERSONAL_UNVERIFIED' | 'EXPIRED'
export type AssistSessionState =
  | 'IDLE'
  | 'CONSENT_PENDING'
  | 'ACTIVE'
  | 'PAUSED'
  | 'COMPLETED'
  | 'STOPPED'
  | 'EXPIRED'

export interface EvidenceRef {
  id: string
  sourceItemId: string
  assetId: string | null
  pageNo: number | null
  charStart: number | null
  charEnd: number | null
  quote: string | null
  bboxJson: string | null
}

export interface SourceItem {
  id: string
  workspaceId: string
  sourceType: SourceType
  sourceApp: string | null
  sourceTitle: string | null
  preview: string | null
  linkUrl: string | null
  privacyLevel: PrivacyLevel
  sourceState: SourceState
  receivedAt: string
  contentHash: string
  lastCheckedAt: string | null
  version: number
  evidence: EvidenceRef[]
  candidateIds: string[]
}

export interface SourceRecheckResult {
  sourceId: string
  changed: boolean
  previousHash: string | null
  currentHash: string | null
  preview: string | null
  checkedAt: string
  requiresReconfirmation: boolean
}

/** 候选：AI/规则只产出建议，必须用户确认才创建事项（指导书 4.3）。 */
export interface Candidate {
  id: string
  workspaceId: string
  sourceItemId: string
  title: string
  summary: string | null
  category: string | null
  rawDueText: string | null
  dueAt: string | null
  dueDate: string | null
  dueTimezone: string | null
  timePrecision: TimePrecision | null
  location: string | null
  materialsJson: string | null
  nextAction: string | null
  confidence: number | null
  fieldConfidenceJson: string | null
  reasonsJson: string | null
  evidenceQuotesJson: string | null
  uncertaintiesJson: string | null
  derivation: Derivation | null
  modelVersion: string | null
  promptVersion: string | null
  schemaVersion: string | null
  needsUserConfirmation: boolean
  status: CandidateStatus
  confirmedTaskId: string | null
  dismissedReason: string | null
  version: number
  createdAt: string
  sourceApp: string | null
  sourcePreview: string | null
}

export interface Reminder {
  id: string
  taskId: string
  triggerAt: string
  channel: ReminderChannel
  state: ReminderState
  dedupeKey: string
  createdAt: string
}

export interface Material {
  id: string
  taskId: string
  name: string
  requiredConditionJson: string | null
  formatRuleJson: string | null
  state: MaterialState
  evidenceRefId: string | null
  assetId: string | null
  version: number
  createdAt: string
  updatedAt: string
}

export interface Comment {
  id: string
  taskId: string
  authorId: string
  body: string
  createdAt: string
  version: number
}

export interface TaskSourceLink {
  id: string
  taskId: string
  sourceItemId: string
  relationType: 'ORIGIN' | 'DUE_DATE' | 'MATERIAL' | 'LOCATION' | 'ACTION' | 'OTHER'
  quote: string | null
  derivation: Derivation
  createdAt: string
  sourceType: string | null
  sourceTitle: string | null
  sourceApp: string | null
  sourceState: string | null
  preview: string | null
}

export interface PendingCompletion {
  completionId: string
  taskId: string
  taskTitle: string
  stepId: string
  stepTitle: string
  submitterId: string
  statement: string | null
  submittedAt: string
  assetIds: string[]
}

export interface MemoryHit {
  documentId: string
  title: string
  snippet: string
  docType: string
  sourceItemId: string | null
  assetId: string | null
  score: number
  createdAt: string
  privacyNote: string | null
}

export interface MemoryContext {
  documentId: string
  title: string
  content: string | null
  docType: string
  privacyNote: string | null
  source: SourceItem | null
  evidence: EvidenceRef[]
  relatedTasks: Task[]
  relatedNotes: string[]
}

export interface ProcessStep {
  id: string
  position: number | null
  instruction: string
  required: boolean | null
  officialEntryUrl: string | null
  conditionJson: string | null
  sourceRefIds: string[]
}

export interface ProcessVersion {
  id: string
  templateId: string
  versionNo: number
  reviewState: ProcessReviewState
  publishedBy: string | null
  publishedAt: string | null
  sourceSnapshotHash: string | null
  version: number
  steps: ProcessStep[]
}

export interface ProcessTemplate {
  id: string
  workspaceId: string
  title: string
  regionCode: string | null
  scopeJson: string | null
  currentVersionId: string | null
  version: number
  createdAt: string
  versions: ProcessVersion[]
}

export interface AssistSession {
  id: string
  requesterId: string
  helperId: string | null
  taskStepId: string | null
  mode: string | null
  state: AssistSessionState
  scopesJson: string | null
  expiresAt: string
  stoppedReason: string | null
  createdAt: string
}

export interface AssistEvent {
  sessionId: string
  seq: number
  eventType: string
  payloadJson: string
  createdAt: string
}

export interface AssistTurnCredentials {
  urls: string[]
  username: string
  credential: string
  ttlSeconds: number
  expiresAt: string
}

export interface AuditEntry {
  id: string
  actorId: string | null
  action: string
  objectType: string | null
  objectId: string | null
  result: string
  requestId: string | null
  metadata: string | null
  createdAt: string
}

export interface ExportJob {
  id: string
  state: string
  sizeBytes: number | null
  errorCode: string | null
  expiresAt: string | null
  createdAt: string
  downloadPath: string | null
}

export type ScanState = 'PENDING' | 'OCR_FAILED' | 'PARSE_FAILED' | 'INDEXED'

export interface Asset {
  id: string
  workspaceId: string
  sha256: string
  mime: string
  sizeBytes: number
  pageCount: number | null
  scanState: ScanState
  ocrFailureCode: string | null
  retentionClass: string
  createdAt: string
}

/** 长截图单段：index/yOffset/height 用于回到原图定位，text 为该段原始识别文本。 */
export interface OcrSegment {
  id: string
  index: number
  yOffset: number
  height: number
  text: string
}

/** GET /assets/{id}/ocr：纯文本 OCR 结果，坐标不可用时不伪造版面位置。 */
export interface OcrResult {
  id: string
  assetId: string
  provider: string
  model: string
  promptVersion: string
  inputSha256: string
  text: string
  imageWidth?: number | null
  imageHeight?: number | null
  segmentCount?: number
  segments?: OcrSegment[]
  coordinatesAvailable: boolean
  createdBy: string
  createdAt: string
}

export type JobState = 'PENDING' | 'RUNNING' | 'SUCCEEDED' | 'DEAD_LETTER'

/** GET /jobs/{id}：不含原始输入与模型密钥的任务状态；分段任务带第 x/y 段进度。 */
export interface JobStatus {
  id: string
  type: string
  state: JobState
  workspaceId: string
  payloadRef: string
  attempts: number
  maxAttempts: number
  lastErrorCode: string | null
  progressCurrent?: number | null
  progressTotal?: number | null
  availableAt: string
  createdAt: string
  updatedAt: string
}

export interface ServerEvent {
  seq: number
  aggregateId: string
  aggregateType: string
  eventType: string
  version: number | null
  payloadJson: string
  createdAt: string
}
