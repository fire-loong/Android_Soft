import { useEffect, useRef, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Alert, App, Button, Space, Spin, Tag, Typography } from 'antd'
import { assetApi, getErrorCode, getErrorMessage, jobApi, ocrApi } from '../lib/api'
import { describeOcrFailure } from '../lib/aiProvenance'
import { formatDateTime } from '../lib/format'
import type { Asset, JobStatus, OcrResult } from '../lib/types'

const POLL_INTERVAL_MS = 1500
const POLL_TIMEOUT_MS = 180_000

function isImage(asset: Asset | undefined): boolean {
  if (!asset?.mime) return false
  const mime = asset.mime.toLowerCase().split(';')[0].trim()
  return mime === 'image/png' || mime === 'image/jpeg'
}

function isNoResult(error: unknown): boolean {
  return getErrorCode(error) === 'RESOURCE_NOT_FOUND'
}

/**
 * 证据文件的 OCR 结果区（指导书 4.3 / 5.3 / 14.5）：
 * - 有结果时明确标注“OCR 识别”，并说明纯文本无坐标、需对照原文件；
 * - 失败或未识别时给出可执行降级路径，不静默当作成功；
 * - 云端识别必须由用户逐次显式授权。
 */
export default function OcrPanel({ assetId, workspaceId }: { assetId: string; workspaceId: string }) {
  const { message, modal } = App.useApp()
  const queryClient = useQueryClient()
  const [jobId, setJobId] = useState<string | null>(null)
  const [failedJobId, setFailedJobId] = useState<string | null>(null)
  const pollStartedAt = useRef<number | null>(null)

  const assetQuery = useQuery({
    queryKey: ['asset', assetId, workspaceId],
    queryFn: () => assetApi.get(assetId, workspaceId)
  })

  const ocrQuery = useQuery<OcrResult | null>({
    queryKey: ['ocr', assetId, workspaceId],
    queryFn: async () => {
      try {
        return await ocrApi.result(assetId, workspaceId)
      } catch (err) {
        if (isNoResult(err)) return null
        throw err
      }
    },
    retry: false
  })

  const startJob = (job: JobStatus) => {
    if (job.state === 'DEAD_LETTER') {
      setFailedJobId(job.id)
      void queryClient.invalidateQueries({ queryKey: ['asset', assetId, workspaceId] })
      message.error(`OCR 识别未完成：${describeOcrFailure(job.lastErrorCode)}。图片仍可查看原文件。`)
      return
    }
    setFailedJobId(null)
    pollStartedAt.current = Date.now()
    setJobId(job.id)
  }

  const enqueueMut = useMutation({
    mutationFn: () => ocrApi.enqueue(assetId, workspaceId),
    onSuccess: (job) => {
      startJob(job)
      if (job.state !== 'DEAD_LETTER') {
        message.info('已加入 OCR 识别队列；完成后会在本区域显示文字')
      }
    },
    onError: (err) => message.error(getErrorMessage(err, '无法提交 OCR 识别'))
  })

  const retryMut = useMutation({
    mutationFn: () => jobApi.retry(failedJobId!, workspaceId),
    onSuccess: (job) => {
      // 重试沿用同一 job id：先覆盖缓存中的 DEAD_LETTER，避免旧状态立刻把轮询关掉。
      queryClient.setQueryData(['job', job.id, workspaceId], job)
      startJob(job)
      message.info('已重新加入 OCR 识别队列')
    },
    onError: (err) => message.error(getErrorMessage(err, '任务重试失败，请稍后再试'))
  })

  const jobQuery = useQuery<JobStatus>({
    queryKey: ['job', jobId, workspaceId],
    queryFn: () => jobApi.get(jobId!, workspaceId),
    enabled: !!jobId,
    retry: false,
    refetchInterval: (query) => {
      const state = query.state.data?.state
      if (state !== 'PENDING' && state !== 'RUNNING') return false
      const started = pollStartedAt.current
      if (started != null && Date.now() - started > POLL_TIMEOUT_MS) return false
      return POLL_INTERVAL_MS
    }
  })

  useEffect(() => {
    const state = jobQuery.data?.state
    if (state === 'SUCCEEDED') {
      setJobId(null)
      pollStartedAt.current = null
      void queryClient.invalidateQueries({ queryKey: ['ocr', assetId, workspaceId] })
      void queryClient.invalidateQueries({ queryKey: ['asset', assetId, workspaceId] })
    } else if (state === 'DEAD_LETTER') {
      if (jobId) setFailedJobId(jobId)
      message.error(
        `OCR 识别未完成：${describeOcrFailure(jobQuery.data?.lastErrorCode ?? null)}。图片仍可查看原文件。`
      )
      setJobId(null)
      pollStartedAt.current = null
      void queryClient.invalidateQueries({ queryKey: ['asset', assetId, workspaceId] })
    }
  }, [jobQuery.data?.state, jobQuery.data?.lastErrorCode, jobId, assetId, workspaceId, queryClient, message])

  // 查询任务状态本身失败（403/404/网络）时停止轮询，避免“识别中”永久卡住。
  useEffect(() => {
    if (jobQuery.isError) {
      message.error(`无法查询 OCR 任务状态：${getErrorMessage(jobQuery.error)}。可稍后刷新查看结果。`)
      setJobId(null)
      pollStartedAt.current = null
    }
  }, [jobQuery.isError, jobQuery.error, message])

  // 排队/执行超时后停止轮询并明确告知，不无限轮询也不假装成功。
  useEffect(() => {
    if (!jobId) return
    const timer = window.setTimeout(() => {
      message.warning('OCR 识别长时间未完成，已停止自动刷新。可稍后刷新页面查看结果。')
      setJobId(null)
      pollStartedAt.current = null
    }, POLL_TIMEOUT_MS + POLL_INTERVAL_MS)
    return () => window.clearTimeout(timer)
  }, [jobId, message])

  const confirmAndEnqueue = () => {
    modal.confirm({
      title: '将图片上传到云端 OCR？',
      content:
        '此操作会把这张图片发送到云端 OCR 服务识别文字。识别结果只作为候选依据，仍需你核对；识别失败会明确提示，不会把失败当作成功。',
      okText: '授权并识别',
      cancelText: '取消',
      onOk: () => enqueueMut.mutateAsync()
    })
  }

  if (assetQuery.isLoading) {
    return <Spin size="small" />
  }
  if (assetQuery.error) {
    return (
      <Typography.Text type="secondary" style={{ fontSize: 12 }}>
        无法读取文件状态：{getErrorMessage(assetQuery.error)}
      </Typography.Text>
    )
  }

  const asset = assetQuery.data
  const result = ocrQuery.data
  const running = !!jobId || enqueueMut.isPending || retryMut.isPending

  return (
    <Space direction="vertical" size={6} style={{ width: '100%', marginTop: 8 }}>
      <Space wrap size={4}>
        <Tag color="blue">OCR 文字识别</Tag>
        <Tag>{asset?.mime ?? '未知类型'}</Tag>
        {asset?.scanState && <Tag color={asset.scanState === 'OCR_FAILED' ? 'red' : 'default'}>{asset.scanState}</Tag>}
        {running && <Tag color="processing">识别中…</Tag>}
      </Space>

      {ocrQuery.isLoading ? (
        <Spin size="small" />
      ) : ocrQuery.error ? (
        <Alert type="error" showIcon message={`读取 OCR 结果失败：${getErrorMessage(ocrQuery.error)}`} />
      ) : result ? (
        <Space direction="vertical" size={6} style={{ width: '100%' }}>
          <Alert
            type="info"
            showIcon
            message="以下文字由 OCR 识别生成，可能存在错别字或漏字；请对照原文件核对后再使用。"
            description={
              <Space wrap size={4}>
                <Tag>{result.provider}</Tag>
                <Tag>模型 {result.model}</Tag>
                <Tag>提示词 {result.promptVersion}</Tag>
                <Tag color={result.coordinatesAvailable ? 'green' : 'gold'}>
                  {result.coordinatesAvailable ? '有版面坐标' : '仅纯文本，无版面坐标'}
                </Tag>
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                  生成于 {formatDateTime(result.createdAt)}
                </Typography.Text>
              </Space>
            }
          />
          <Typography.Paragraph
            style={{ whiteSpace: 'pre-wrap', marginBottom: 0, maxHeight: 280, overflow: 'auto' }}
          >
            {result.text}
          </Typography.Paragraph>
        </Space>
      ) : running ? (
        <Alert
          type="info"
          showIcon
          message="正在识别图片文字…"
          description="完成后本区域会自动更新；失败会明确提示，不会把失败当作成功。"
        />
      ) : asset?.scanState === 'OCR_FAILED' ? (
        <Alert
          type="warning"
          showIcon
          message={`OCR 识别失败：${describeOcrFailure(asset.ocrFailureCode)}`}
          description="图片仍可查看原文件。你可以在“来源管理”手动填写摘要后生成候选；系统不会把失败结果当作识别成功。"
          action={
            failedJobId ? (
              <Button size="small" loading={running} onClick={() => retryMut.mutate()}>
                重试识别
              </Button>
            ) : undefined
          }
        />
      ) : isImage(asset) ? (
        <Space direction="vertical" size={4}>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            尚未识别文字。识别需把图片上传到云端 OCR，必须由你显式授权。
          </Typography.Text>
          <Button size="small" loading={running} onClick={confirmAndEnqueue}>
            识别图片文字（需授权上传）
          </Button>
        </Space>
      ) : (
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          当前文件类型不支持云端 OCR；仍可查看原文件并手动填写摘要。
        </Typography.Text>
      )}
    </Space>
  )
}
