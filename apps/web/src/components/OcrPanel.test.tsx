import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { App } from 'antd'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import OcrPanel from './OcrPanel'
import { assetApi, jobApi, ocrApi } from '../lib/api'
import type { Asset, OcrResult } from '../lib/types'

vi.mock('../lib/api', () => ({
  assetApi: { get: vi.fn() },
  jobApi: { get: vi.fn(), retry: vi.fn() },
  ocrApi: { result: vi.fn(), enqueue: vi.fn() },
  getErrorCode: (err: unknown) =>
    (err as { response?: { data?: { error?: { code?: string } } } })?.response?.data?.error?.code,
  getErrorMessage: (err: unknown) => (err as Error)?.message ?? '请求失败'
}))

function notFound(): Error {
  return Object.assign(new Error('该模型尚未生成 OCR 结果'), {
    response: { data: { error: { code: 'RESOURCE_NOT_FOUND', message: '该模型尚未生成 OCR 结果' } } }
  })
}

function asset(overrides: Partial<Asset> = {}): Asset {
  return {
    id: 'a1',
    workspaceId: 'w1',
    sha256: 'abc',
    mime: 'image/png',
    sizeBytes: 1024,
    pageCount: null,
    scanState: 'PENDING',
    ocrFailureCode: null,
    retentionClass: 'STANDARD',
    createdAt: '2026-11-20T00:00:00Z',
    ...overrides
  }
}

function ocrResult(overrides: Partial<OcrResult> = {}): OcrResult {
  return {
    id: 'o1',
    assetId: 'a1',
    provider: 'maas',
    model: 'ocr-model',
    promptVersion: 'ocr-prompt-v1',
    inputSha256: 'abc',
    text: '请于 11 月 30 日前提交课程设计报告',
    coordinatesAvailable: false,
    createdBy: 'u1',
    createdAt: '2026-11-20T00:00:00Z',
    ...overrides
  }
}

function renderPanel() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <App>
        <OcrPanel assetId="a1" workspaceId="w1" />
      </App>
    </QueryClientProvider>
  )
}

describe('OcrPanel：OCR 结果标注与失败降级', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    Object.defineProperty(window, 'matchMedia', {
      writable: true,
      value: (query: string) => ({
        matches: false,
        media: query,
        onchange: null,
        addListener: vi.fn(),
        removeListener: vi.fn(),
        addEventListener: vi.fn(),
        removeEventListener: vi.fn(),
        dispatchEvent: vi.fn()
      })
    })
    vi.mocked(jobApi.get).mockRejectedValue(new Error('unused'))
  })

  it('有 OCR 结果时明确标注来源、模型与“仅纯文本无坐标”', async () => {
    vi.mocked(assetApi.get).mockResolvedValue(asset({ scanState: 'INDEXED' }))
    vi.mocked(ocrApi.result).mockResolvedValue(ocrResult())

    renderPanel()

    expect(await screen.findByText(/请于 11 月 30 日前提交课程设计报告/)).toBeInTheDocument()
    expect(screen.getByText('OCR 文字识别')).toBeInTheDocument()
    expect(screen.getByText('仅纯文本，无版面坐标')).toBeInTheDocument()
    expect(screen.getByText(/可能存在错别字或漏字/)).toBeInTheDocument()
    expect(screen.queryByText('识别图片文字（需授权上传）')).not.toBeInTheDocument()
  })

  it('OCR 失败时明确提示原因并保留原文件与重试路径', async () => {
    vi.mocked(assetApi.get).mockResolvedValue(asset({ scanState: 'OCR_FAILED', ocrFailureCode: 'OCR_UNAVAILABLE' }))
    vi.mocked(ocrApi.result).mockRejectedValue(notFound())

    renderPanel()

    expect(await screen.findByText(/OCR 识别失败/)).toBeInTheDocument()
    expect(screen.getByText(/OCR 服务暂不可用/)).toBeInTheDocument()
    expect(screen.getByText(/图片仍可查看原文件/)).toBeInTheDocument()
    expect(screen.getByText(/手动填写摘要/)).toBeInTheDocument()
    // 刷新后拿不到原任务 id，不应展示一个实际复用死信幂等键的“重试”按钮。
    expect(screen.queryByText('重试识别')).not.toBeInTheDocument()
  })

  it('未识别时点击按钮只弹出授权确认，确认前不会上传', async () => {
    vi.mocked(assetApi.get).mockResolvedValue(asset())
    vi.mocked(ocrApi.result).mockRejectedValue(notFound())
    vi.mocked(ocrApi.enqueue).mockResolvedValue({
      id: 'j1',
      type: 'ASSET_OCR',
      state: 'PENDING',
      workspaceId: 'w1',
      payloadRef: 'asset:a1',
      attempts: 0,
      maxAttempts: 5,
      lastErrorCode: null,
      availableAt: '2026-11-20T00:00:00Z',
      createdAt: '2026-11-20T00:00:00Z',
      updatedAt: '2026-11-20T00:00:00Z'
    })

    renderPanel()

    const button = await screen.findByText('识别图片文字（需授权上传）')
    fireEvent.click(button)
    expect((await screen.findAllByText('将图片上传到云端 OCR？')).length).toBeGreaterThan(0)
    expect(ocrApi.enqueue).not.toHaveBeenCalled()

    fireEvent.click((await screen.findAllByText('授权并识别'))[0])
    await waitFor(() => expect(ocrApi.enqueue).toHaveBeenCalledWith('a1', 'w1'))
  })

  it('同一会话内的死信任务通过 /jobs/{id}/retry 重试，而不是复用旧幂等键', async () => {
    vi.mocked(assetApi.get)
      .mockResolvedValueOnce(asset())
      .mockResolvedValue(asset({ scanState: 'OCR_FAILED', ocrFailureCode: 'OCR_UNAVAILABLE' }))
    vi.mocked(ocrApi.result).mockRejectedValueOnce(notFound()).mockResolvedValue(ocrResult())
    const deadJob = {
      id: 'jdead',
      type: 'ASSET_OCR',
      state: 'DEAD_LETTER' as const,
      workspaceId: 'w1',
      payloadRef: 'asset:a1',
      attempts: 5,
      maxAttempts: 5,
      lastErrorCode: 'OCR_UNAVAILABLE',
      availableAt: '2026-11-20T00:00:00Z',
      createdAt: '2026-11-20T00:00:00Z',
      updatedAt: '2026-11-20T00:00:00Z'
    }
    vi.mocked(ocrApi.enqueue).mockResolvedValue(deadJob)
    vi.mocked(jobApi.retry).mockResolvedValue({ ...deadJob, state: 'PENDING', attempts: 0, lastErrorCode: null })
    vi.mocked(jobApi.get).mockResolvedValue({ ...deadJob, state: 'SUCCEEDED', attempts: 1 })

    renderPanel()

    fireEvent.click(await screen.findByText('识别图片文字（需授权上传）'))
    fireEvent.click((await screen.findAllByText('授权并识别'))[0])

    const retryButton = await screen.findByText('重试识别')
    fireEvent.click(retryButton)

    await waitFor(() => expect(jobApi.retry).toHaveBeenCalledWith('jdead', 'w1'))
    // retry 写入同 id 的 PENDING 状态后，轮询应继续到终态并刷新出 OCR 结果。
    expect(jobApi.get).toHaveBeenCalledWith('jdead', 'w1')
    expect(await screen.findByText(/请于 11 月 30 日前提交课程设计报告/)).toBeInTheDocument()
  })
})
