import { useMemo, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import dayjs, { type Dayjs } from 'dayjs'
import {
  Alert,
  Badge,
  Button,
  Card,
  DatePicker,
  Descriptions,
  Drawer,
  Empty,
  Form,
  Input,
  List,
  Modal,
  Popconfirm,
  Segmented,
  Select,
  Space,
  Spin,
  Switch,
  Tag,
  Typography,
  message
} from 'antd'
import { useNavigate } from 'react-router-dom'
import EvidenceList from '../components/EvidenceList'
import QueryState from '../components/QueryState'
import {
  getErrorCode,
  getErrorMessage,
  inboxApi,
  sourceApi,
  type InboxConfirmPayload
} from '../lib/api'
import { useAuth } from '../lib/auth'
import { SOURCE_STATE_META, formatDateTime, relativeTime } from '../lib/format'
import type { Candidate, CandidateStatus } from '../lib/types'

const REASON_LABEL: Record<string, string> = {
  MULTIPLE_DATES: '原文含多个候选日期，需人工确认',
  NO_DATE: '未识别到明确日期',
  LOW_CONFIDENCE_DUE: '日期识别置信度偏低',
  SENSITIVE: '内容包含敏感信息，仅保留本地',
  DUPLICATE_SOURCE: '与已有来源重复',
  RULE_MATCH: '规则命中',
  AI_SUGGESTED: 'AI 建议，需人工确认'
}

function parseStringArray(json: string | null): string[] {
  if (!json) return []
  try {
    const v = JSON.parse(json)
    if (Array.isArray(v)) return v.map((x) => String(x)).filter(Boolean)
    return []
  } catch {
    return []
  }
}

function parseReasons(json: string | null): string[] {
  if (!json) return []
  try {
    const v = JSON.parse(json)
    if (Array.isArray(v)) return v.map((x) => (typeof x === 'string' ? x : JSON.stringify(x)))
    if (v && typeof v === 'object') return Object.keys(v)
  } catch {
    return [json]
  }
  return []
}

function confidenceColor(c: number | null): string {
  if (c == null) return 'default'
  if (c >= 0.85) return 'green'
  if (c >= 0.6) return 'gold'
  return 'red'
}

export default function Inbox() {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const { currentWorkspace } = useAuth()
  const workspaceId = currentWorkspace?.id
  const [status, setStatus] = useState<CandidateStatus>('PENDING')
  const [cursor, setCursor] = useState<string | undefined>(undefined)
  const [confirming, setConfirming] = useState<Candidate | null>(null)
  const [viewing, setViewing] = useState<Candidate | null>(null)

  const listQuery = useQuery({
    queryKey: ['inbox', workspaceId, status, cursor],
    queryFn: () => inboxApi.list(workspaceId!, status, cursor, 20),
    enabled: !!workspaceId
  })

  const sourceQuery = useQuery({
    queryKey: ['source', viewing?.sourceItemId, workspaceId],
    queryFn: () => sourceApi.get(viewing!.sourceItemId, workspaceId!),
    enabled: !!viewing && !!workspaceId
  })

  const dismissMut = useMutation({
    mutationFn: ({ candidate, reason }: { candidate: Candidate; reason?: string }) =>
      inboxApi.dismiss(candidate.id, candidate.version, reason),
    onSuccess: () => {
      message.success('已忽略，不会创建事项或提醒')
      void queryClient.invalidateQueries({ queryKey: ['inbox', workspaceId] })
      void queryClient.invalidateQueries({ queryKey: ['today', workspaceId] })
    },
    onError: (err) => message.error(getErrorMessage(err, '忽略失败'))
  })

  const items = listQuery.data?.items ?? []

  return (
    <Space direction="vertical" style={{ width: '100%' }} size="middle">
      <Space style={{ width: '100%', justifyContent: 'space-between', flexWrap: 'wrap' }}>
        <Space direction="vertical" size={0}>
          <Typography.Title level={4} style={{ margin: 0 }}>
            待确认收件箱
          </Typography.Title>
          <Typography.Text type="secondary">
            通知、分享、截图和文件先成为候选；只有你确认后才会创建事项与提醒。
          </Typography.Text>
        </Space>
        <Segmented
          value={status}
          onChange={(v) => {
            setCursor(undefined)
            setStatus(v as CandidateStatus)
          }}
          options={[
            { value: 'PENDING', label: '待确认' },
            { value: 'CONFIRMED', label: '已确认' },
            { value: 'DISMISSED', label: '已忽略' }
          ]}
        />
      </Space>

      <QueryState
        loading={listQuery.isLoading}
        error={listQuery.error}
        empty={items.length === 0}
        emptyText={status === 'PENDING' ? '没有待确认候选。可从 Android 分享通知/截图，或在“来源管理”导入文本与文件。' : '该状态下暂无候选'}
        onRetry={() => void listQuery.refetch()}
      >
        <List
          dataSource={items}
          renderItem={(c) => (
            <Card
              size="small"
              style={{ marginBottom: 12 }}
              title={
                <Space wrap>
                  <Typography.Text strong>{c.title}</Typography.Text>
                  <Tag color={confidenceColor(c.confidence)}>
                    置信度 {c.confidence == null ? '—' : `${Math.round(c.confidence * 100)}%`}
                  </Tag>
                  {c.derivation && <Tag>{c.derivation === 'RULE' ? '规则抽取' : c.derivation === 'AI' ? 'AI 候选' : c.derivation}</Tag>}
                  {parseReasons(c.reasonsJson).includes('MULTIPLE_DATES') && <Tag color="orange">多个日期</Tag>}
                </Space>
              }
              extra={<Typography.Text type="secondary">{relativeTime(c.createdAt)}</Typography.Text>}
            >
              <Space direction="vertical" style={{ width: '100%' }} size={8}>
                <Typography.Paragraph type="secondary" style={{ marginBottom: 0 }} ellipsis={{ rows: 2 }}>
                  {c.summary || c.sourcePreview || '（无摘要）'}
                </Typography.Paragraph>
                <Space wrap size={16}>
                  <Typography.Text>截止：{c.dueAt ? formatDateTime(c.dueAt) : c.dueDate ?? '未识别'}</Typography.Text>
                  <Typography.Text>来源：{c.sourceApp ?? '未知 App'}</Typography.Text>
                  {c.location && <Typography.Text>地点：{c.location}</Typography.Text>}
                  {c.nextAction && <Typography.Text>下一步：{c.nextAction}</Typography.Text>}
                </Space>
                <Space wrap>
                  <Button onClick={() => setViewing(c)}>查看原文证据</Button>
                  {c.status === 'PENDING' ? (
                    <>
                      <Button type="primary" onClick={() => setConfirming(c)}>
                        确认并创建事项
                      </Button>
                      <Popconfirm
                        title="忽略该候选？"
                        description="不会创建事项或提醒，仅保留可查看记录。"
                        okText="忽略"
                        cancelText="取消"
                        onConfirm={() => dismissMut.mutate({ candidate: c, reason: '用户忽略' })}
                      >
                        <Button danger loading={dismissMut.isPending}>
                          忽略
                        </Button>
                      </Popconfirm>
                    </>
                  ) : c.confirmedTaskId ? (
                    <Button onClick={() => navigate(`/tasks/${c.confirmedTaskId}`)}>查看事项</Button>
                  ) : (
                    <Tag>{c.dismissedReason ? `忽略原因：${c.dismissedReason}` : '已处理'}</Tag>
                  )}
                </Space>
              </Space>
            </Card>
          )}
        />

        <Space style={{ width: '100%', justifyContent: 'space-between' }}>
          <Button disabled={!cursor} onClick={() => setCursor(undefined)}>
            回到第一页
          </Button>
          <Space>
            <Badge count={items.length} showZero color="#1677ff" />
            <Button
              disabled={!listQuery.data?.hasMore}
              onClick={() => setCursor(listQuery.data?.nextCursor ?? undefined)}
            >
              下一页
            </Button>
          </Space>
        </Space>
      </QueryState>

      <ConfirmModal
        candidate={confirming}
        workspaceId={workspaceId}
        onClose={() => setConfirming(null)}
        onConfirmed={(taskId) => {
          setConfirming(null)
          void queryClient.invalidateQueries({ queryKey: ['inbox', workspaceId] })
          void queryClient.invalidateQueries({ queryKey: ['tasks', workspaceId] })
          void queryClient.invalidateQueries({ queryKey: ['today', workspaceId] })
          Modal.confirm({
            title: '事项已创建',
            content: '已写入事项、步骤、材料与提醒，并保留来源证据。',
            okText: '查看事项',
            cancelText: '留在收件箱',
            onOk: () => navigate(`/tasks/${taskId}`)
          })
        }}
      />

      <Drawer
        title={viewing ? `原文来源 · ${viewing.sourceApp ?? ''}` : '原文来源'}
        width={640}
        open={!!viewing}
        onClose={() => setViewing(null)}
      >
        {sourceQuery.isLoading ? (
          <Spin />
        ) : sourceQuery.error ? (
          <Alert type="error" showIcon message={getErrorMessage(sourceQuery.error)} />
        ) : sourceQuery.data ? (
          <Space direction="vertical" style={{ width: '100%' }}>
            <Descriptions column={1} size="small" bordered>
              <Descriptions.Item label="来源类型">{sourceQuery.data.sourceType}</Descriptions.Item>
              <Descriptions.Item label="可信状态">
                <Tag color={SOURCE_STATE_META[sourceQuery.data.sourceState]?.color}>
                  {SOURCE_STATE_META[sourceQuery.data.sourceState]?.label}
                </Tag>
              </Descriptions.Item>
              <Descriptions.Item label="收到时间">{formatDateTime(sourceQuery.data.receivedAt)}</Descriptions.Item>
              <Descriptions.Item label="原文预览">
                <Typography.Paragraph style={{ whiteSpace: 'pre-wrap', marginBottom: 0 }}>
                  {sourceQuery.data.preview ?? '—'}
                </Typography.Paragraph>
              </Descriptions.Item>
            </Descriptions>
            <Typography.Title level={5}>证据引用</Typography.Title>
            <EvidenceList evidence={sourceQuery.data.evidence} workspaceId={workspaceId!} />
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              未经核验的社区内容不会标记为官方政策；无来源字段显示“来源未说明”。
            </Typography.Text>
          </Space>
        ) : (
          <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="来源不可用" />
        )}
      </Drawer>
    </Space>
  )
}

function ConfirmModal({
  candidate,
  workspaceId,
  onClose,
  onConfirmed
}: {
  candidate: Candidate | null
  workspaceId?: string
  onClose: () => void
  onConfirmed: (taskId: string) => void
}) {
  const [form] = Form.useForm<{
    title: string
    dueAt?: Dayjs
    dueDateConfirmed: boolean
    steps?: string
    materials?: string[]
    notes?: string
    reminder?: 'none' | '1h' | '3h' | '1d'
  }>()
  const [submitting, setSubmitting] = useState(false)

  const reasons = useMemo(() => parseReasons(candidate?.reasonsJson ?? null), [candidate])
  const needsDateConfirm = reasons.includes('MULTIPLE_DATES')

  const initial = useMemo(() => {
    if (!candidate) return undefined
    return {
      title: candidate.title,
      dueAt: candidate.dueAt ? dayjs(candidate.dueAt) : undefined,
      dueDateConfirmed: false,
      steps: candidate.nextAction ?? '',
      materials: parseStringArray(candidate.materialsJson),
      notes: ''
    }
  }, [candidate])

  const submit = async () => {
    if (!candidate || !workspaceId) return
    const v = await form.validateFields()
    const steps = (v.steps ?? '')
      .split('\n')
      .map((s) => s.trim())
      .filter(Boolean)
    let reminders: InboxConfirmPayload['reminders']
    if (v.dueAt && v.reminder && v.reminder !== 'none') {
      const hours = v.reminder === '1h' ? 1 : v.reminder === '3h' ? 3 : 24
      const trigger = v.dueAt.subtract(hours, 'hour')
      if (trigger.isAfter(dayjs())) {
        reminders = [{ triggerAt: trigger.toISOString(), channel: 'NOTIFICATION' }]
      }
    }
    setSubmitting(true)
    try {
      const result = await inboxApi.confirm(candidate.id, {
        baseVersion: candidate.version,
        title: v.title,
        dueAt: v.dueAt ? v.dueAt.toISOString() : null,
        dueTimezone: Intl.DateTimeFormat().resolvedOptions().timeZone,
        dueDateConfirmed: v.dueDateConfirmed,
        notes: v.notes,
        steps: steps.length ? steps : undefined,
        materials: v.materials?.length ? v.materials : undefined,
        reminders
      })
      onConfirmed(result.taskId)
    } catch (err) {
      if (getErrorCode(err) === 'VALIDATION_FAILED') {
        message.warning(getErrorMessage(err, '请核对日期与必填字段'))
      } else {
        message.error(getErrorMessage(err, '确认失败'))
      }
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <Modal
      title="确认候选并创建事项"
      open={!!candidate}
      onCancel={onClose}
      onOk={() => void submit()}
      okText="确认创建"
      cancelText="取消"
      confirmLoading={submitting}
      width={620}
      destroyOnHidden
      afterOpenChange={(open) => {
        if (open && initial) form.setFieldsValue(initial)
      }}
    >
      {candidate && (
        <Space direction="vertical" style={{ width: '100%' }} size="middle">
          <Alert
            type="info"
            showIcon
            message="AI/规则只生成候选，本操作由你确认后才会写入事项与提醒。"
            description={`来源：${candidate.sourceApp ?? '未知'} · 置信度 ${candidate.confidence == null ? '—' : Math.round(candidate.confidence * 100) + '%'}`}
          />
          {(needsDateConfirm || (candidate.confidence != null && candidate.confidence < 0.6)) && (
            <Alert
              type="warning"
              showIcon
              message={needsDateConfirm ? '原文含多个日期' : '日期置信度偏低'}
              description="请对照原文核实截止时间；未显式勾选“已核对日期”时，后端会拒绝创建截止提醒。"
            />
          )}
          <Form form={form} layout="vertical" initialValues={initial}>
            <Form.Item label="事项标题" name="title" rules={[{ required: true, message: '请输入标题' }, { max: 200 }]}>
              <Input maxLength={200} />
            </Form.Item>
            <Form.Item label="截止时间（可留空，表示无明确截止）" name="dueAt">
              <DatePicker showTime style={{ width: '100%' }} format="YYYY-MM-DD HH:mm" />
            </Form.Item>
            <Form.Item
              label="我已对照原文核对截止时间"
              name="dueDateConfirmed"
              valuePropName="checked"
              tooltip="低置信度或多日期时必须显式确认，系统不会静默挑选日期"
            >
              <Switch />
            </Form.Item>
            <Form.Item label="步骤（每行一个，留空则由规则生成）" name="steps">
              <Input.TextArea rows={3} maxLength={2000} />
            </Form.Item>
            <Form.Item label="所需材料" name="materials">
              <Select mode="tags" placeholder="输入后回车，例如：学生证" tokenSeparators={[',', '，']} />
            </Form.Item>
            <Form.Item label="提前提醒" name="reminder" initialValue="none">
              <Select
                options={[
                  { value: 'none', label: '不创建提醒' },
                  { value: '1h', label: '提前 1 小时' },
                  { value: '3h', label: '提前 3 小时' },
                  { value: '1d', label: '提前 1 天' }
                ]}
              />
            </Form.Item>
            <Form.Item label="备注（选填）" name="notes">
              <Input.TextArea rows={2} maxLength={2000} />
            </Form.Item>
          </Form>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            未确认候选不会产生强提醒；忽略后可随时在“已忽略”中查看。
          </Typography.Text>
        </Space>
      )}
    </Modal>
  )
}
