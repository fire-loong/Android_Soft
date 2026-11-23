import { describe, expect, it } from 'vitest'
import {
  LOW_CONFIDENCE_THRESHOLD,
  candidateFieldPresence,
  derivationLabel,
  describeOcrFailure,
  fieldConfidenceEntries,
  formatConfidence,
  hasLowConfidenceField,
  parseFieldConfidence,
  parseStringList
} from './aiProvenance'
import type { Candidate } from './types'

function candidate(overrides: Partial<Candidate> = {}): Candidate {
  return {
    id: 'c1',
    workspaceId: 'w1',
    sourceItemId: 's1',
    title: '提交课程设计材料',
    summary: '11 月 30 日前提交 PDF',
    category: 'SUBMISSION',
    rawDueText: '11 月 30 日前',
    dueAt: '2026-11-30T15:59:00Z',
    dueDate: null,
    dueTimezone: 'Asia/Shanghai',
    timePrecision: 'MINUTE',
    location: null,
    materialsJson: '["课程设计报告"]',
    nextAction: '整理 PDF 并上传',
    confidence: 0.82,
    fieldConfidenceJson: '{"title":0.9,"due":0.35,"time":0.8,"location":0,"materials":0.6}',
    reasonsJson: '["MULTIPLE_DATES"]',
    evidenceQuotesJson: '["请于11月30日前提交"]',
    uncertaintiesJson: '["原文含多个日期，已选择最接近截止的日期"]',
    derivation: 'AI',
    modelVersion: 'qwen-plus',
    promptVersion: 'prompt-v1',
    schemaVersion: 'notification-task-v1',
    needsUserConfirmation: true,
    status: 'PENDING',
    confirmedTaskId: null,
    dismissedReason: null,
    version: 1,
    createdAt: '2026-11-20T00:00:00Z',
    sourceApp: 'com.tencent.mm',
    sourcePreview: '通知原文',
    ...overrides
  }
}

describe('AI 推导来源与置信度展示', () => {
  it('所有已知推导来源都有明确中文标注', () => {
    expect(derivationLabel('AI')).toBe('由 AI 生成')
    expect(derivationLabel('RULE')).toBe('规则抽取')
    expect(derivationLabel('USER_CONFIRMED_AI')).toBe('用户确认的 AI 候选')
    expect(derivationLabel('OFFICIAL')).toBe('官方来源')
  })

  it('未知推导来源原样展示，便于发现后端新增枚举', () => {
    expect(derivationLabel('OCR')).toBe('OCR')
    expect(derivationLabel(null)).toBe('来源未说明')
  })

  it('只接受 0..1 的数字，脏数据不会展示为确定结果', () => {
    const parsed = parseFieldConfidence('{"title":0.9,"due":"0.4","time":-1,"location":2,"materials":0.5}')
    expect(parsed).toEqual({ title: 0.9, materials: 0.5 })
    expect(parseFieldConfidence('not-json')).toEqual({})
  })

  it('日期字段低置信被单独标黄，不被整体置信掩盖', () => {
    const entries = fieldConfidenceEntries(candidate())
    const due = entries.find((e) => e.field === 'due')
    expect(due?.low).toBe(true)
    expect(due?.confidence).toBe(0.35)
    expect(due?.missing).toBe(false)
    expect(hasLowConfidenceField(candidate())).toBe(true)
  })

  it('字段没有值时为“未识别”，不能算作低置信', () => {
    const entries = fieldConfidenceEntries(candidate())
    const location = entries.find((e) => e.field === 'location')
    expect(location?.missing).toBe(true)
    expect(location?.low).toBe(false)
  })

  it('有值但缺少逐字段置信时标记为“置信未提供”，不冒充已核对', () => {
    const c = candidate({ fieldConfidenceJson: '{}' })
    const entries = fieldConfidenceEntries(c)
    expect(entries.find((e) => e.field === 'due')?.unknownConfidence).toBe(true)
    expect(entries.find((e) => e.field === 'due')?.missing).toBe(false)
    expect(entries.find((e) => e.field === 'location')?.missing).toBe(true)
    expect(hasLowConfidenceField(c)).toBe(false)
  })

  it('有值且达到阈值时不标黄', () => {
    const c = candidate({ fieldConfidenceJson: `{"title":0.9,"due":${LOW_CONFIDENCE_THRESHOLD}}` })
    expect(hasLowConfidenceField(c)).toBe(false)
  })

  it('字段存在性按实际内容判断', () => {
    const presence = candidateFieldPresence(candidate({ location: '学院办公室', dueAt: null, dueDate: '2026-11-30' }))
    expect(presence.due).toBe(true)
    expect(presence.time).toBe(false)
    expect(presence.location).toBe(true)
    expect(presence.materials).toBe(true)
  })

  it('只有原文日期文本但未解析时，日期字段仍是“未识别”', () => {
    const c = candidate({ dueAt: null, dueDate: null, rawDueText: '11 月 30 日前' })
    expect(candidateFieldPresence(c).due).toBe(false)
    expect(fieldConfidenceEntries(c).find((e) => e.field === 'due')?.missing).toBe(true)
  })

  it('置信度格式化与空值展示', () => {
    expect(formatConfidence(0.826)).toBe('83%')
    expect(formatConfidence(null)).toBe('—')
  })

  it('字符串数组解析对非法输入返回空数组', () => {
    expect(parseStringList('["a","b"]')).toEqual(['a', 'b'])
    expect(parseStringList('{"a":1}')).toEqual([])
    expect(parseStringList('broken')).toEqual([])
  })
})

describe('OCR 失败降级文案', () => {
  it('已知失败码给出明确原因与降级提示', () => {
    expect(describeOcrFailure('OCR_UNAVAILABLE')).toContain('暂不可用')
    expect(describeOcrFailure('OCR_OUTPUT_INVALID')).toContain('无法解析')
    expect(describeOcrFailure('UNSUPPORTED_MEDIA_TYPE')).toContain('仅支持')
  })

  it('未知或空失败码不会伪装成成功', () => {
    expect(describeOcrFailure('SOMETHING_NEW')).toContain('SOMETHING_NEW')
    expect(describeOcrFailure(null)).toContain('未生成文字结果')
  })
})
