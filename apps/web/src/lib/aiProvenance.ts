import type { Candidate, Derivation } from './types'

/**
 * AI/OCR 结果展示的纯函数（指导书 4.3 / 9.4 / 14.5）。
 * 只做文案与状态映射，不改变后端字段，也不替用户判断真假。
 */

/** 与后端 ai.min-confidence-for-reminder 默认值保持一致。 */
export const LOW_CONFIDENCE_THRESHOLD = 0.6

const FIELD_LABELS: Record<string, string> = {
  title: '标题',
  due: '截止日期',
  time: '具体时间',
  location: '地点',
  materials: '所需材料'
}

export interface FieldConfidenceEntry {
  field: string
  label: string
  confidence: number | null
  /** 字段有值但置信度低于阈值：必须逐项标黄并提示核对。 */
  low: boolean
  /** 字段没有值（未识别），不是“低置信”，不能冒充确认结果。 */
  missing: boolean
  /** 字段有值但抽取器没有给出置信度：既不能算低置信，也不能当作已核对。 */
  unknownConfidence: boolean
}

/** 推导来源的中文说明；未知值原样返回，便于发现后端新增枚举。 */
export function derivationLabel(derivation: Derivation | string | null): string {
  switch (derivation) {
    case 'AI':
      return '由 AI 生成'
    case 'RULE':
      return '规则抽取'
    case 'USER':
      return '用户填写'
    case 'OFFICIAL':
      return '官方来源'
    case 'USER_CONFIRMED_AI':
      return '用户确认的 AI 候选'
    default:
      return derivation ?? '来源未说明'
  }
}

export function parseObject(json: string | null): Record<string, unknown> {
  if (!json) return {}
  try {
    const v = JSON.parse(json)
    return v && typeof v === 'object' && !Array.isArray(v) ? (v as Record<string, unknown>) : {}
  } catch {
    return {}
  }
}

/** 解析逐字段置信 JSON；忽略非数字或越界值，避免把脏数据展示成确定结果。 */
export function parseFieldConfidence(json: string | null): Record<string, number> {
  const raw = parseObject(json)
  const result: Record<string, number> = {}
  for (const [key, value] of Object.entries(raw)) {
    if (typeof value === 'number' && Number.isFinite(value) && value >= 0 && value <= 1) {
      result[key] = value
    }
  }
  return result
}

/** 解析字符串数组 JSON（reasons / uncertainties / evidenceQuotes 等）。 */
export function parseStringList(json: string | null): string[] {
  if (!json) return []
  try {
    const v = JSON.parse(json)
    if (Array.isArray(v)) return v.map((x) => (typeof x === 'string' ? x : JSON.stringify(x))).filter(Boolean)
    return []
  } catch {
    return []
  }
}

export function isLowConfidence(confidence: number | null | undefined): boolean {
  return confidence != null && confidence < LOW_CONFIDENCE_THRESHOLD
}

export function formatConfidence(confidence: number | null | undefined): string {
  if (confidence == null || !Number.isFinite(confidence)) return '—'
  return `${Math.round(confidence * 100)}%`
}

/** 候选各字段是否有实际值，用于区分“未识别”和“低置信”。 */
export function candidateFieldPresence(candidate: Candidate): Record<string, boolean> {
  return {
    title: Boolean(candidate.title?.trim()),
    // 只有已解析出日期才算字段有值；仅 rawDueText 表示“有原文但未解析”，不能显示为高置信。
    due: Boolean(candidate.dueAt || candidate.dueDate),
    time: Boolean(candidate.dueAt),
    location: Boolean(candidate.location?.trim()),
    materials: parseStringList(candidate.materialsJson).length > 0
  }
}

export function fieldConfidenceEntries(candidate: Candidate): FieldConfidenceEntry[] {
  const confidences = parseFieldConfidence(candidate.fieldConfidenceJson)
  const presence = candidateFieldPresence(candidate)
  return Object.keys(FIELD_LABELS).map((field) => {
    const confidence = confidences[field] ?? null
    const hasValue = presence[field] ?? false
    return {
      field,
      label: FIELD_LABELS[field],
      confidence,
      low: hasValue && isLowConfidence(confidence),
      missing: !hasValue,
      unknownConfidence: hasValue && confidence == null
    }
  })
}

/** 是否存在任何低置信字段；用于在候选卡片上给出整体提醒。 */
export function hasLowConfidenceField(candidate: Candidate): boolean {
  return fieldConfidenceEntries(candidate).some((e) => e.low)
}

/** OCR 失败码的可读说明；未知码原样拼接，便于排障。 */
export function describeOcrFailure(code: string | null): string {
  switch (code) {
    case 'OCR_UNAVAILABLE':
      return 'OCR 服务暂不可用（网络、超时或服务端未配置）'
    case 'OCR_OUTPUT_INVALID':
      return 'OCR 返回内容无法解析，未写入识别结果'
    case 'UNSUPPORTED_MEDIA_TYPE':
      return '当前文件类型不支持云端 OCR（仅支持 PNG/JPEG 图片）'
    case 'ASSET_INTEGRITY_MISMATCH':
      return '文件内容与上传记录不一致，未发送到云端'
    case 'PERMISSION_DENIED':
      return '缺少显式授权或空间权限，未执行识别'
    default:
      return code ? `识别失败（${code}）` : '识别失败，未生成文字结果'
  }
}
