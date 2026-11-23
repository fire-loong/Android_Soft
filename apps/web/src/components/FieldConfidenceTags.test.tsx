import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import FieldConfidenceTags from './FieldConfidenceTags'
import type { Candidate } from '../lib/types'

function candidate(overrides: Partial<Candidate> = {}): Candidate {
  return {
    id: 'c1',
    workspaceId: 'w1',
    sourceItemId: 's1',
    title: '提交课程设计材料',
    summary: null,
    category: 'SUBMISSION',
    rawDueText: '11 月 30 日前',
    dueAt: '2026-11-30T15:59:00Z',
    dueDate: null,
    dueTimezone: 'Asia/Shanghai',
    timePrecision: 'MINUTE',
    location: null,
    materialsJson: '["课程设计报告"]',
    nextAction: null,
    confidence: 0.82,
    fieldConfidenceJson: '{"title":0.9,"due":0.35,"time":0.8,"location":0,"materials":0.6}',
    reasonsJson: '[]',
    evidenceQuotesJson: '[]',
    uncertaintiesJson: '[]',
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
    sourcePreview: null,
    ...overrides
  }
}

describe('FieldConfidenceTags', () => {
  it('低置信日期单独标黄，未识别字段明确显示未识别', () => {
    render(<FieldConfidenceTags candidate={candidate()} />)
    expect(screen.getByText('截止日期 35% · 待核对')).toBeInTheDocument()
    expect(screen.getByText('地点 未识别')).toBeInTheDocument()
    expect(screen.getByText('标题 90%')).toBeInTheDocument()
    expect(screen.getByText(/不是精确概率/)).toBeInTheDocument()
  })

  it('有值但缺少逐字段置信时显示“置信未提供”，不冒充已确认', () => {
    render(<FieldConfidenceTags candidate={candidate({ fieldConfidenceJson: '{}' })} />)
    expect(screen.getAllByText('截止日期 置信未提供').length).toBeGreaterThan(0)
    expect(screen.getByText(/未提供逐字段置信度/)).toBeInTheDocument()
  })
})
