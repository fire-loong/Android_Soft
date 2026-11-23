import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { App } from 'antd'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import OcrPanel from './OcrPanel'
import OcrSegmentPreview from './OcrSegmentPreview'
import { assetApi, jobApi, ocrApi } from '../lib/api'
import type { Asset, OcrResult } from '../lib/types'

vi.mock('../lib/api', () => ({
  assetApi: { get: vi.fn(), downloadUrl: vi.fn() },
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

function longScreenshotResult(overrides: Partial<OcrResult> = {}): OcrResult {
  return ocrResult({
    text: '课程通知标题\n截止 11 月 30 日\n提交 PDF 到学习通',
    imageWidth: 400,
    imageHeight: 2400,
    segmentCount: 2,
    segments: [
      { id: 's1', index: 0, yOffset: 0, height: 1400, text: '课程通知标题\n截止 11 月 30 日' },
      { id: 's2', index: 1, yOffset: 1220, height: 1180, text: '截止 11 月 30 日\n提交 PDF 到学习通' }
    ],
    ...overrides
  })
}

function renderPanel(initialQuery?: string) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <App>
        <OcrPanel assetId="a1" workspaceId="w1" initialQuery={initialQuery} />
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
    expect(screen.queryByLabelText('搜索分段原文')).not.toBeInTheDocument()
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

  it('长截图分段结果展示段序、原图偏移与合并后的全文', async () => {
    vi.mocked(assetApi.get).mockResolvedValue(asset({ scanState: 'INDEXED' }))
    vi.mocked(assetApi.downloadUrl).mockResolvedValue('http://localhost:8080/api/v1/assets/a1/download?token=t')
    vi.mocked(ocrApi.result).mockResolvedValue(longScreenshotResult())

    renderPanel()

    expect(await screen.findByText('长截图分段 2 段')).toBeInTheDocument()
    expect(screen.getByText('第 1 段')).toBeInTheDocument()
    expect(screen.getByText('第 2 段')).toBeInTheDocument()
    expect(screen.getByText(/第 1 段对应原图 y=0–1400/)).toBeInTheDocument()
    expect(await screen.findByAltText('长截图原图')).toBeInTheDocument()
    expect(screen.getAllByText(/提交 PDF 到学习通/).length).toBeGreaterThan(0)

    fireEvent.click(screen.getByText('第 2 段'))
    expect(await screen.findByText(/第 2 段对应原图 y=1220–2400/)).toBeInTheDocument()
    expect(assetApi.downloadUrl).toHaveBeenCalledWith('a1', 'w1')
  })

  it('从个人记忆带入关键词时自动定位命中段，图片失败仍保留文本定位', async () => {
    vi.mocked(assetApi.get).mockResolvedValue(asset({ scanState: 'INDEXED' }))
    vi.mocked(assetApi.downloadUrl).mockResolvedValue('http://localhost:8080/api/v1/assets/a1/download?token=t')
    vi.mocked(ocrApi.result).mockResolvedValue(longScreenshotResult())

    renderPanel('学习通')

    expect(await screen.findByText(/第 2 段对应原图 y=1220–2400/)).toBeInTheDocument()
    expect(screen.getByDisplayValue('学习通')).toBeInTheDocument()
    expect(screen.getByText('学习通', { selector: 'mark' })).toBeInTheDocument()
    expect(screen.getByText(/定位到词语所在段/)).toBeInTheDocument()

    const image = await screen.findByAltText('长截图原图')
    const band = image.nextElementSibling as HTMLElement
    const scroller = image.parentElement?.parentElement as HTMLElement
    Object.defineProperty(band, 'offsetTop', { configurable: true, value: 1200 })
    Object.defineProperty(scroller, 'clientHeight', { configurable: true, value: 300 })
    scroller.scrollTop = 0
    fireEvent.load(image)
    await waitFor(() => expect(scroller.scrollTop).toBe(1100))

    fireEvent.error(image)
    expect(await screen.findByText(/原图加载失败/)).toBeInTheDocument()
    expect(screen.getByText('学习通', { selector: 'mark' })).toBeInTheDocument()
    expect(screen.getByText(/第 2 段对应原图 y=1220–2400/)).toBeInTheDocument()
  })

  it('同资产同 URL 切换 OCR 结果后仍保留图片加载状态并正确滚动', async () => {
    vi.mocked(assetApi.downloadUrl).mockResolvedValue('http://localhost:8080/api/v1/assets/a1/download?token=t')
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const preview = (result: OcrResult, initialQuery?: string) => (
      <QueryClientProvider client={client}>
        <App>
          <OcrSegmentPreview
            assetId="a1"
            workspaceId="w1"
            result={result}
            initialQuery={initialQuery}
          />
        </App>
      </QueryClientProvider>
    )
    const view = render(preview(longScreenshotResult()))
    const image = await screen.findByAltText('长截图原图')
    fireEvent.load(image)

    const band = image.nextElementSibling as HTMLElement
    const scroller = image.parentElement?.parentElement as HTMLElement
    Object.defineProperty(band, 'offsetTop', { configurable: true, value: 1200 })
    Object.defineProperty(scroller, 'clientHeight', { configurable: true, value: 300 })
    scroller.scrollTop = 0

    view.rerender(preview(longScreenshotResult({ id: 'o2' }), '学习通'))

    expect(await screen.findByText(/第 2 段对应原图 y=1220–2400/)).toBeInTheDocument()
    await waitFor(() => expect(scroller.scrollTop).toBe(1100))
  }, 15_000)

  it('关键词搜索支持多段循环、英文大小写与同段多处高亮', async () => {
    vi.mocked(assetApi.get).mockResolvedValue(asset({ scanState: 'INDEXED' }))
    vi.mocked(assetApi.downloadUrl).mockResolvedValue('http://localhost:8080/api/v1/assets/a1/download?token=t')
    vi.mocked(ocrApi.result).mockResolvedValue(
      longScreenshotResult({
        segments: [
          { id: 's1', index: 0, yOffset: 0, height: 1400, text: '课程通知标题\n截止 11 月 30 日' },
          { id: 's2', index: 1, yOffset: 1220, height: 1180, text: 'PDF 与 pdf 均需提交\n截止 11 月 30 日' }
        ]
      })
    )

    renderPanel()
    const input = await screen.findByLabelText('搜索分段原文')
    fireEvent.change(input, { target: { value: 'pdf' } })
    fireEvent.click(screen.getByRole('button', { name: /定\s*位/ }))

    expect(await screen.findByText(/第 2 段对应原图 y=1220–2400/)).toBeInTheDocument()
    await waitFor(() => expect(document.querySelectorAll('mark')).toHaveLength(2))
    expect(screen.getByText('命中 1 段')).toBeInTheDocument()

    fireEvent.change(input, { target: { value: '截止' } })
    fireEvent.click(screen.getByRole('button', { name: /定\s*位/ }))
    expect(await screen.findByText(/第 1 段对应原图 y=0–1400/)).toBeInTheDocument()
    expect(screen.getByText('命中 2 段')).toBeInTheDocument()

    fireEvent.click(screen.getByText('下一命中段'))
    expect(await screen.findByText(/第 2 段对应原图 y=1220–2400/)).toBeInTheDocument()
    fireEvent.click(screen.getByText('上一命中段'))
    expect(await screen.findByText(/第 1 段对应原图 y=0–1400/)).toBeInTheDocument()
  }, 15_000)

  it('无命中与清空搜索均明确反馈且不破坏现有分段预览', async () => {
    vi.mocked(assetApi.get).mockResolvedValue(asset({ scanState: 'INDEXED' }))
    vi.mocked(assetApi.downloadUrl).mockResolvedValue('http://localhost:8080/api/v1/assets/a1/download?token=t')
    vi.mocked(ocrApi.result).mockResolvedValue(longScreenshotResult())

    renderPanel()
    const input = await screen.findByLabelText('搜索分段原文')
    fireEvent.change(input, { target: { value: '不存在的词语' } })
    fireEvent.click(screen.getByRole('button', { name: /定\s*位/ }))

    expect(await screen.findByText('未找到“不存在的词语”')).toBeInTheDocument()
    expect(screen.getByText(/第 1 段对应原图 y=0–1400/)).toBeInTheDocument()

    fireEvent.change(input, { target: { value: '' } })
    expect(screen.queryByText(/未找到/)).not.toBeInTheDocument()
    expect(screen.queryByText(/命中 \d+ 段/)).not.toBeInTheDocument()
    expect(screen.getByText(/第 1 段对应原图 y=0–1400/)).toBeInTheDocument()
  })

  it('分段识别过程中展示第 x/y 段进度', async () => {
    vi.mocked(assetApi.get).mockResolvedValue(asset())
    vi.mocked(ocrApi.result).mockRejectedValue(notFound())
    const runningJob = {
      id: 'j-long',
      type: 'ASSET_OCR',
      state: 'RUNNING' as const,
      workspaceId: 'w1',
      payloadRef: 'asset:a1',
      attempts: 1,
      maxAttempts: 5,
      lastErrorCode: null,
      progressCurrent: 1,
      progressTotal: 2,
      availableAt: '2026-11-20T00:00:00Z',
      createdAt: '2026-11-20T00:00:00Z',
      updatedAt: '2026-11-20T00:00:00Z'
    }
    vi.mocked(ocrApi.enqueue).mockResolvedValue({ ...runningJob, state: 'PENDING', progressCurrent: null, progressTotal: null })
    vi.mocked(jobApi.get).mockResolvedValue(runningJob)

    renderPanel()

    fireEvent.click(await screen.findByText('识别图片文字（需授权上传）'))
    fireEvent.click((await screen.findAllByText('授权并识别'))[0])

    expect(await screen.findByText('正在分段识别长截图（第 1/2 段）')).toBeInTheDocument()
    expect(screen.getByText('识别中 1/2 段')).toBeInTheDocument()
  })
})
