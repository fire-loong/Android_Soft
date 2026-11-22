import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import dayjs, { type Dayjs } from 'dayjs'
import {
  Alert,
  Button,
  Card,
  Col,
  Collapse,
  DatePicker,
  Descriptions,
  Drawer,
  Form,
  Input,
  List,
  Popconfirm,
  Row,
  Select,
  Space,
  Spin,
  Tag,
  Typography,
  Upload,
  message
} from 'antd'
import type { UploadFile } from 'antd'
import { useNavigate, useParams } from 'react-router-dom'
import {
  assetApi,
  commentApi,
  completionApi,
  getErrorMessage,
  materialApi,
  reminderApi,
  reviewApi,
  stepApi,
  taskApi,
  taskSourceApi,
  type CreateStepPayload,
  type TransitionPayload,
  type UpdateTaskPayload
} from '../lib/api'
import { useAuth } from '../lib/auth'
import {
  MATERIAL_STATE_META,
  REMINDER_STATE_META,
  REVIEW_STATE_META,
  STEP_STATUS_META,
  TASK_STATUS_META,
  formatDateTime,
  relativeTime
} from '../lib/format'
import type {
  Completion,
  Material,
  MaterialState,
  Reminder,
  ReminderChannel,
  Step,
  Task,
  TaskStatus
} from '../lib/types'

export default function TaskDetail() {
  const { id } = useParams<{ id: string }>()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const { currentWorkspace } = useAuth()
  const workspaceId = currentWorkspace?.id
  const [editOpen, setEditOpen] = useState(false)
  const [editForm] = Form.useForm<{ title: string; description?: string; priority?: number; dueDate?: Dayjs }>()

  const taskQuery = useQuery({
    queryKey: ['task', id],
    queryFn: () => taskApi.get(id!, workspaceId!),
    enabled: !!id && !!workspaceId
  })
  const stepsQuery = useQuery({
    queryKey: ['steps', id],
    queryFn: () => stepApi.list(id!, workspaceId!),
    enabled: !!id && !!workspaceId
  })

  const task = taskQuery.data
  const invalidateAll = () => {
    void queryClient.invalidateQueries({ queryKey: ['task', id] })
    void queryClient.invalidateQueries({ queryKey: ['steps', id] })
    void queryClient.invalidateQueries({ queryKey: ['tasks', workspaceId] })
  }

  const transitionMut = useMutation({
    mutationFn: (payload: TransitionPayload) => taskApi.transition(id!, workspaceId!, payload),
    onSuccess: () => {
      message.success('状态已更新')
      invalidateAll()
    },
    onError: (err) => message.error(getErrorMessage(err, '状态转换失败'))
  })

  const deleteMut = useMutation({
    mutationFn: (baseVersion: number) => taskApi.remove(id!, workspaceId!, baseVersion),
    onSuccess: () => {
      message.success('事项已删除')
      void queryClient.invalidateQueries({ queryKey: ['tasks', workspaceId] })
      navigate('/today', { replace: true })
    },
    onError: (err) => message.error(getErrorMessage(err, '删除失败'))
  })

  const updateMut = useMutation({
    mutationFn: (payload: UpdateTaskPayload) => taskApi.update(id!, workspaceId!, payload),
    onSuccess: () => {
      message.success('已保存')
      setEditOpen(false)
      invalidateAll()
    },
    onError: (err) => message.error(getErrorMessage(err, '保存失败'))
  })

  const createStepMut = useMutation({
    mutationFn: (payload: CreateStepPayload) => stepApi.create(id!, workspaceId!, payload),
    onSuccess: () => {
      message.success('步骤已添加')
      void queryClient.invalidateQueries({ queryKey: ['steps', id] })
    },
    onError: (err) => message.error(getErrorMessage(err, '添加步骤失败'))
  })

  const openEdit = () => {
    if (!task) return
    editForm.setFieldsValue({
      title: task.title,
      description: task.description ?? undefined,
      priority: task.priority ?? undefined
    })
    setEditOpen(true)
  }

  if (taskQuery.isLoading) return <Spin />
  if (!task) return <Typography.Text type="secondary">未找到该事项</Typography.Text>

  return (
    <Space direction="vertical" style={{ width: '100%' }} size="middle">
      <Card>
        <Space style={{ width: '100%', justifyContent: 'space-between' }} align="start">
          <div>
            <Typography.Title level={4} style={{ margin: 0 }}>
              {task.title}
            </Typography.Title>
            <Space style={{ marginTop: 8 }}>
              <Tag color={TASK_STATUS_META[task.status]?.color}>
                {TASK_STATUS_META[task.status]?.label}
              </Tag>
              <Typography.Text type="secondary">版本 v{task.version}</Typography.Text>
            </Space>
          </div>
          <Space>
            <Button onClick={openEdit}>编辑</Button>
            <Popconfirm
              title="确定删除该事项？"
              description="删除后不可恢复"
              okText="删除"
              okButtonProps={{ danger: true }}
              onConfirm={() => deleteMut.mutate(task.version)}
            >
              <Button danger loading={deleteMut.isPending}>
                删除
              </Button>
            </Popconfirm>
          </Space>
        </Space>

        <Descriptions column={2} size="small" style={{ marginTop: 16 }}>
          <Descriptions.Item label="截止">{task.dueAt ? formatDateTime(task.dueAt) : task.dueDate ?? '—'}</Descriptions.Item>
          <Descriptions.Item label="优先级">{task.priority ?? '—'}</Descriptions.Item>
          <Descriptions.Item label="创建时间">{formatDateTime(task.createdAt)}</Descriptions.Item>
          <Descriptions.Item label="更新时间">{formatDateTime(task.updatedAt)}</Descriptions.Item>
          <Descriptions.Item label="描述" span={2}>
            {task.description || '—'}
          </Descriptions.Item>
        </Descriptions>
      </Card>

      <Card title="状态转换">
        <Space wrap>
          <Typography.Text>当前：</Typography.Text>
          <Tag color={TASK_STATUS_META[task.status]?.color}>{TASK_STATUS_META[task.status]?.label}</Tag>
          <Typography.Text>→ 目标：</Typography.Text>
          <TransitionBar task={task} onTransition={(p) => transitionMut.mutate(p)} loading={transitionMut.isPending} />
        </Space>
      </Card>

      <SourceTraceCard taskId={task.id} workspaceId={workspaceId!} />

      <Row gutter={16}>
        <Col xs={24} lg={12}>
          <MaterialsCard taskId={task.id} workspaceId={workspaceId!} />
        </Col>
        <Col xs={24} lg={12}>
          <RemindersCard taskId={task.id} workspaceId={workspaceId!} />
        </Col>
      </Row>

      <Card
        title="步骤"
        extra={
          <AddStepForm
            onAdd={(payload) => createStepMut.mutate(payload)}
            loading={createStepMut.isPending}
          />
        }
      >
        {stepsQuery.isLoading ? (
          <Spin />
        ) : stepsQuery.data && stepsQuery.data.length > 0 ? (
          <Collapse
            items={stepsQuery.data.map((step) => ({
              key: step.id,
              label: (
                <Space>
                  <Tag color={STEP_STATUS_META[step.status]?.color}>
                    {STEP_STATUS_META[step.status]?.label}
                  </Tag>
                  <span>{step.title}</span>
                  {step.instruction && (
                    <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                      {step.instruction}
                    </Typography.Text>
                  )}
                </Space>
              ),
              children: <StepRow key={step.id} step={step} workspaceId={workspaceId!} />
            }))}
          />
        ) : (
          <Typography.Text type="secondary">暂无步骤，在右上角添加</Typography.Text>
        )}
      </Card>

      <CommentsCard taskId={task.id} workspaceId={workspaceId!} />

      <Drawer
        title="编辑事项"
        open={editOpen}
        onClose={() => setEditOpen(false)}
        width={420}
        extra={
          <Button type="primary" loading={updateMut.isPending} onClick={() => editForm.submit()}>
            保存
          </Button>
        }
      >
        <Form
          form={editForm}
          layout="vertical"
          onFinish={(v) => {
            const payload: UpdateTaskPayload = {
              baseVersion: task.version,
              title: v.title,
              description: v.description ?? null,
              priority: v.priority ?? null,
              dueDate: v.dueDate ? v.dueDate.format('YYYY-MM-DD') : null
            }
            updateMut.mutate(payload)
          }}
        >
          <Form.Item label="标题" name="title" rules={[{ required: true }, { max: 128 }]}>
            <Input maxLength={128} />
          </Form.Item>
          <Form.Item label="描述" name="description">
            <Input.TextArea rows={3} maxLength={4000} />
          </Form.Item>
          <Form.Item label="截止日期" name="dueDate">
            <DatePicker style={{ width: '100%' }} format="YYYY-MM-DD" />
          </Form.Item>
          <Form.Item label="优先级" name="priority">
            <Select
              allowClear
              options={[0, 1, 2, 3].map((p) => ({ value: p, label: String(p) }))}
            />
          </Form.Item>
        </Form>
      </Drawer>
    </Space>
  )
}

function TransitionBar({
  task,
  onTransition,
  loading
}: {
  task: Task
  onTransition: (p: TransitionPayload) => void
  loading: boolean
}) {
  const [target, setTarget] = useState<TaskStatus | undefined>(undefined)
  const [reason, setReason] = useState('')
  const allowed = task.allowedTransitions ?? []
  return (
    <Space>
      <Select
        style={{ width: 160 }}
        placeholder={allowed.length ? '选择目标状态' : '当前状态无可用转换'}
        value={target}
        onChange={setTarget}
        disabled={allowed.length === 0}
        options={allowed.map((s) => ({
          value: s,
          label: TASK_STATUS_META[s].label
        }))}
      />
      <Input
        style={{ width: 220 }}
        placeholder="转换原因（选填）"
        value={reason}
        onChange={(e) => setReason(e.target.value)}
        maxLength={500}
      />
      <Button
        type="primary"
        disabled={!target}
        loading={loading}
        onClick={() => target && onTransition({ status: target, baseVersion: task.version, reason: reason || undefined })}
      >
        转换
      </Button>
    </Space>
  )
}

function AddStepForm({
  onAdd,
  loading
}: {
  onAdd: (payload: CreateStepPayload) => void
  loading: boolean
}) {
  const [form] = Form.useForm<{ title: string; instruction?: string }>()
  return (
    <Form
      form={form}
      layout="inline"
      onFinish={(v) => {
        onAdd({ title: v.title, instruction: v.instruction })
        form.resetFields()
      }}
    >
      <Form.Item name="title" rules={[{ required: true, message: '标题' }]}>
        <Input placeholder="步骤标题" style={{ width: 160 }} />
      </Form.Item>
      <Form.Item name="instruction">
        <Input placeholder="说明（选填）" style={{ width: 200 }} />
      </Form.Item>
      <Form.Item>
        <Button type="primary" htmlType="submit" loading={loading}>
          添加步骤
        </Button>
      </Form.Item>
    </Form>
  )
}

const RELATION_LABEL: Record<string, string> = {
  ORIGIN: '事项来源',
  DUE_DATE: '截止时间依据',
  MATERIAL: '材料依据',
  LOCATION: '地点依据',
  ACTION: '下一步依据',
  OTHER: '其他关联'
}

function SourceTraceCard({ taskId, workspaceId }: { taskId: string; workspaceId: string }) {
  const navigate = useNavigate()
  const query = useQuery({
    queryKey: ['task-sources', taskId],
    queryFn: () => taskSourceApi.list(taskId, workspaceId)
  })
  return (
    <Card
      title="来源证据"
      extra={
        <Button size="small" onClick={() => navigate('/sources')}>
          来源管理
        </Button>
      }
    >
      {query.isLoading ? (
        <Spin size="small" />
      ) : query.error ? (
        <Alert type="error" showIcon message={getErrorMessage(query.error)} />
      ) : (query.data ?? []).length === 0 ? (
        <Typography.Text type="secondary">
          该事项没有关联来源（手工创建或来源已删除）。无来源字段不会标注为官方政策。
        </Typography.Text>
      ) : (
        <List
          size="small"
          dataSource={query.data ?? []}
          renderItem={(link) => (
            <List.Item>
              <Space direction="vertical" size={2} style={{ width: '100%' }}>
                <Space wrap>
                  <Tag color="blue">{RELATION_LABEL[link.relationType] ?? link.relationType}</Tag>
                  {link.sourceState && <Tag>{link.sourceState}</Tag>}
                  <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                    {link.sourceTitle ?? link.sourceApp ?? '未命名来源'}
                  </Typography.Text>
                </Space>
                <Typography.Text style={{ whiteSpace: 'pre-wrap' }}>{link.quote ?? '（无引文）'}</Typography.Text>
              </Space>
            </List.Item>
          )}
        />
      )}
    </Card>
  )
}

function MaterialsCard({ taskId, workspaceId }: { taskId: string; workspaceId: string }) {
  const queryClient = useQueryClient()
  const [form] = Form.useForm<{ name: string; state: MaterialState }>()
  const query = useQuery({
    queryKey: ['materials', taskId],
    queryFn: () => materialApi.list(taskId, workspaceId)
  })
  const invalidate = () => void queryClient.invalidateQueries({ queryKey: ['materials', taskId] })

  const createMut = useMutation({
    mutationFn: (v: { name: string; state: MaterialState }) => materialApi.create(taskId, workspaceId, v),
    onSuccess: () => {
      message.success('材料已添加')
      form.resetFields()
      invalidate()
    },
    onError: (err) => message.error(getErrorMessage(err, '添加失败'))
  })
  const updateMut = useMutation({
    mutationFn: ({ material, state }: { material: Material; state: MaterialState }) =>
      materialApi.update(taskId, material.id, workspaceId, { baseVersion: material.version, state }),
    onSuccess: () => {
      message.success('材料状态已更新')
      invalidate()
    },
    onError: (err) => message.error(getErrorMessage(err, '更新失败'))
  })
  const deleteMut = useMutation({
    mutationFn: (materialId: string) => materialApi.remove(taskId, materialId, workspaceId),
    onSuccess: () => {
      message.success('材料已删除')
      invalidate()
    },
    onError: (err) => message.error(getErrorMessage(err, '删除失败'))
  })

  return (
    <Card title="材料清单" size="small">
      <Form
        form={form}
        layout="inline"
        style={{ marginBottom: 12 }}
        initialValues={{ state: 'MISSING' }}
        onFinish={(v) => createMut.mutate(v)}
      >
        <Form.Item name="name" rules={[{ required: true, message: '材料名称' }]}>
          <Input placeholder="材料名称" maxLength={128} style={{ width: 170 }} />
        </Form.Item>
        <Form.Item name="state">
          <Select
            style={{ width: 110 }}
            options={(Object.keys(MATERIAL_STATE_META) as MaterialState[]).map((s) => ({
              value: s,
              label: MATERIAL_STATE_META[s].label
            }))}
          />
        </Form.Item>
        <Form.Item>
          <Button type="primary" htmlType="submit" loading={createMut.isPending}>
            添加
          </Button>
        </Form.Item>
      </Form>
      {query.isLoading ? (
        <Spin size="small" />
      ) : (query.data ?? []).length === 0 ? (
        <Typography.Text type="secondary">暂无材料要求</Typography.Text>
      ) : (
        <List
          size="small"
          dataSource={query.data ?? []}
          renderItem={(m) => (
            <List.Item
              actions={[
                <Select
                  key="state"
                  size="small"
                  style={{ width: 100 }}
                  value={m.state}
                  onChange={(state: MaterialState) => updateMut.mutate({ material: m, state })}
                  options={(Object.keys(MATERIAL_STATE_META) as MaterialState[]).map((s) => ({
                    value: s,
                    label: MATERIAL_STATE_META[s].label
                  }))}
                />,
                <Popconfirm key="del" title="删除该材料？" onConfirm={() => deleteMut.mutate(m.id)}>
                  <Button size="small" danger>
                    删除
                  </Button>
                </Popconfirm>
              ]}
            >
              <Space>
                <Tag color={MATERIAL_STATE_META[m.state]?.color}>{MATERIAL_STATE_META[m.state]?.label}</Tag>
                {m.name}
              </Space>
            </List.Item>
          )}
        />
      )}
    </Card>
  )
}

function RemindersCard({ taskId, workspaceId }: { taskId: string; workspaceId: string }) {
  const queryClient = useQueryClient()
  const [at, setAt] = useState<Dayjs | null>(null)
  const [channel, setChannel] = useState<ReminderChannel>('NOTIFICATION')
  const query = useQuery({
    queryKey: ['reminders', taskId],
    queryFn: () => reminderApi.list(taskId, workspaceId)
  })
  const invalidate = () => {
    void queryClient.invalidateQueries({ queryKey: ['reminders', taskId] })
    void queryClient.invalidateQueries({ queryKey: ['task', taskId] })
  }

  const replaceMut = useMutation({
    mutationFn: (list: Reminder[]) =>
      reminderApi.replace(
        taskId,
        workspaceId,
        list
          .filter((r) => r.state === 'SCHEDULED')
          .map((r) => ({ triggerAt: r.triggerAt, channel: r.channel }))
      ),
    onSuccess: () => {
      message.success('提醒计划已更新')
      setAt(null)
      invalidate()
    },
    onError: (err) => message.error(getErrorMessage(err, '更新提醒失败'))
  })

  const add = () => {
    if (!at) {
      message.warning('请选择提醒时间')
      return
    }
    if (!at.isAfter(dayjs())) {
      message.warning('提醒时间必须晚于当前时间')
      return
    }
    const existing = query.data ?? []
    replaceMut.mutate([
      ...existing,
      {
        id: `new-${Date.now()}`,
        taskId,
        triggerAt: at.toISOString(),
        channel,
        state: 'SCHEDULED',
        dedupeKey: '',
        createdAt: new Date().toISOString()
      }
    ])
  }

  return (
    <Card title="提醒计划" size="small">
      <Space wrap style={{ marginBottom: 12 }}>
        <DatePicker showTime value={at} onChange={(v) => setAt(v)} format="YYYY-MM-DD HH:mm" />
        <Select
          style={{ width: 120 }}
          value={channel}
          onChange={(v) => setChannel(v)}
          options={[
            { value: 'NOTIFICATION', label: '系统通知' },
            { value: 'IN_APP', label: '应用内' },
            { value: 'HIGH_PRIORITY', label: '强提醒' }
          ]}
        />
        <Button type="primary" loading={replaceMut.isPending} onClick={add}>
          添加
        </Button>
      </Space>
      {query.isLoading ? (
        <Spin size="small" />
      ) : (query.data ?? []).length === 0 ? (
        <Typography.Text type="secondary">暂无提醒；未确认候选不会产生提醒。</Typography.Text>
      ) : (
        <List
          size="small"
          dataSource={query.data ?? []}
          renderItem={(r) => (
            <List.Item
              actions={[
                <Button
                  key="del"
                  size="small"
                  danger
                  disabled={r.state !== 'SCHEDULED'}
                  onClick={() => replaceMut.mutate((query.data ?? []).filter((x) => x.id !== r.id))}
                >
                  取消
                </Button>
              ]}
            >
              <Space wrap>
                <Tag color={REMINDER_STATE_META[r.state]?.color}>{REMINDER_STATE_META[r.state]?.label}</Tag>
                <Typography.Text>{formatDateTime(r.triggerAt)}</Typography.Text>
                <Tag>{r.channel}</Tag>
              </Space>
            </List.Item>
          )}
        />
      )}
    </Card>
  )
}

function CommentsCard({ taskId, workspaceId }: { taskId: string; workspaceId: string }) {
  const queryClient = useQueryClient()
  const [form] = Form.useForm<{ body: string }>()
  const query = useQuery({
    queryKey: ['comments', taskId],
    queryFn: () => commentApi.list(taskId, workspaceId)
  })
  const invalidate = () => void queryClient.invalidateQueries({ queryKey: ['comments', taskId] })
  const createMut = useMutation({
    mutationFn: (body: string) => commentApi.create(taskId, workspaceId, body),
    onSuccess: () => {
      form.resetFields()
      invalidate()
    },
    onError: (err) => message.error(getErrorMessage(err, '发送失败'))
  })
  const deleteMut = useMutation({
    mutationFn: (commentId: string) => commentApi.remove(taskId, commentId, workspaceId),
    onSuccess: () => {
      message.success('评论已删除')
      invalidate()
    },
    onError: (err) => message.error(getErrorMessage(err, '删除失败'))
  })

  return (
    <Card title="协作讨论" size="small">
      <Form form={form} layout="vertical" onFinish={(v) => createMut.mutate(v.body)}>
        <Form.Item name="body" rules={[{ required: true, message: '请输入内容' }, { max: 2000 }]}>
          <Input.TextArea rows={3} maxLength={2000} placeholder="同步进展、补充信息或提醒风险" />
        </Form.Item>
        <Button type="primary" htmlType="submit" loading={createMut.isPending}>
          发表
        </Button>
      </Form>
      {query.isLoading ? (
        <Spin size="small" />
      ) : (query.data ?? []).length === 0 ? (
        <Typography.Text type="secondary">还没有讨论</Typography.Text>
      ) : (
        <List
          size="small"
          dataSource={query.data ?? []}
          renderItem={(c) => (
            <List.Item
              actions={[
                <Popconfirm key="del" title="删除该评论？" onConfirm={() => deleteMut.mutate(c.id)}>
                  <Button size="small" type="text" danger>
                    删除
                  </Button>
                </Popconfirm>
              ]}
            >
              <Space direction="vertical" size={0} style={{ width: '100%' }}>
                <Typography.Text>{c.body}</Typography.Text>
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                  {relativeTime(c.createdAt)}
                </Typography.Text>
              </Space>
            </List.Item>
          )}
        />
      )}
    </Card>
  )
}

function StepRow({ step, workspaceId }: { step: Step; workspaceId: string }) {
  const queryClient = useQueryClient()
  const [form] = Form.useForm<{ statement: string }>()
  const [fileList, setFileList] = useState<UploadFile[]>([])
  const [rejectTarget, setRejectTarget] = useState<Completion | null>(null)
  const [rejectReason, setRejectReason] = useState('')

  const completionsQuery = useQuery({
    queryKey: ['completions', step.id],
    queryFn: () => completionApi.list(step.id, workspaceId)
  })

  const invalidate = () => {
    void queryClient.invalidateQueries({ queryKey: ['completions', step.id] })
    void queryClient.invalidateQueries({ queryKey: ['steps', step.taskId] })
    void queryClient.invalidateQueries({ queryKey: ['task', step.taskId] })
    void queryClient.invalidateQueries({ queryKey: ['review', workspaceId] })
    void queryClient.invalidateQueries({ queryKey: ['tasks', workspaceId] })
  }

  const submitMut = useMutation({
    mutationFn: async (statement: string) => {
      const assetIds: string[] = []
      for (const f of fileList) {
        const file = f.originFileObj as File | undefined
        if (file) {
          const asset = await assetApi.upload(workspaceId, file)
          assetIds.push(asset.id)
        }
      }
      return completionApi.submit(step.id, workspaceId, { statement, assetIds: assetIds.length ? assetIds : undefined })
    },
    onSuccess: () => {
      message.success('完成凭证已提交，等待核验')
      form.resetFields()
      setFileList([])
      invalidate()
    },
    onError: (err) => message.error(getErrorMessage(err, '提交失败'))
  })

  const reviewMut = useMutation({
    mutationFn: ({ completionId, approve, reason }: { completionId: string; approve: boolean; reason?: string }) =>
      reviewApi.review(completionId, approve, reason),
    onSuccess: (_, vars) => {
      message.success(vars.approve ? '已通过核验' : '已退回')
      setRejectTarget(null)
      setRejectReason('')
      invalidate()
    },
    onError: (err) => message.error(getErrorMessage(err, '核验失败'))
  })

  return (
    <Space direction="vertical" style={{ width: '100%' }} size="middle">
      <Row gutter={16}>
        <Col span={24}>
          <Typography.Text strong>已提交的完成凭证</Typography.Text>
        </Col>
        <Col span={24}>
          {completionsQuery.isLoading ? (
            <Spin size="small" />
          ) : (completionsQuery.data ?? []).length === 0 ? (
            <Typography.Text type="secondary">暂无凭证</Typography.Text>
          ) : (
            <Space direction="vertical" style={{ width: '100%' }}>
              {(completionsQuery.data ?? []).map((c: Completion) => (
                <Card key={c.id} size="small" type="inner">
                  <Space style={{ width: '100%', justifyContent: 'space-between' }} align="start">
                    <Space direction="vertical" size={2}>
                      <Typography.Text>{c.statement || '（无说明）'}</Typography.Text>
                      <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                        提交于 {formatDateTime(c.submittedAt)}
                        {c.assetIds.length > 0 ? ` · 附件 ${c.assetIds.length} 项` : ''}
                        {c.reviewReason ? ` · 核验说明：${c.reviewReason}` : ''}
                      </Typography.Text>
                    </Space>
                    <Tag color={REVIEW_STATE_META[c.reviewState]?.color}>
                      {REVIEW_STATE_META[c.reviewState]?.label}
                    </Tag>
                  </Space>
                  {c.reviewState === 'PENDING' && (
                    <Space style={{ marginTop: 8 }}>
                      <Button
                        size="small"
                        type="primary"
                        loading={reviewMut.isPending}
                        onClick={() => reviewMut.mutate({ completionId: c.id, approve: true })}
                      >
                        通过核验
                      </Button>
                      <Button size="small" danger onClick={() => setRejectTarget(c)}>
                        退回
                      </Button>
                    </Space>
                  )}
                  {c.assetIds.length > 0 && (
                    <Space wrap style={{ marginTop: 8 }}>
                      {c.assetIds.map((aid) => (
                        <Button
                          key={aid}
                          size="small"
                          onClick={() =>
                            void assetApi
                              .downloadUrl(aid, workspaceId)
                              .then((url) => window.open(url, '_blank', 'noopener,noreferrer'))
                              .catch(() => message.error('无法获取附件地址'))
                          }
                        >
                          附件 {aid.slice(0, 6)}
                        </Button>
                      ))}
                    </Space>
                  )}
                </Card>
              ))}
            </Space>
          )}
        </Col>
      </Row>
      <Form form={form} layout="vertical" onFinish={(v) => submitMut.mutate(v.statement)}>
        <Form.Item
          label="提交完成说明"
          name="statement"
          rules={[{ required: true, message: '请填写完成说明' }]}
        >
          <Input.TextArea rows={2} maxLength={2000} placeholder="说明你如何完成了该步骤" />
        </Form.Item>
        <Form.Item label="附件（选填，上传即去重）">
          <Upload
            fileList={fileList}
            maxCount={5}
            beforeUpload={() => false}
            onChange={({ fileList: fl }) => setFileList(fl)}
          >
            <Button>选择文件</Button>
          </Upload>
        </Form.Item>
        <Button type="primary" htmlType="submit" loading={submitMut.isPending}>
          提交完成凭证
        </Button>
      </Form>

      {rejectTarget && (
        <Card size="small" title="退回凭证">
          <Input.TextArea
            rows={2}
            maxLength={500}
            value={rejectReason}
            onChange={(e) => setRejectReason(e.target.value)}
            placeholder="说明需要补充或修正的内容"
          />
          <Space style={{ marginTop: 8 }}>
            <Button onClick={() => setRejectTarget(null)}>取消</Button>
            <Button
              danger
              type="primary"
              loading={reviewMut.isPending}
              onClick={() =>
                reviewMut.mutate({ completionId: rejectTarget.id, approve: false, reason: rejectReason })
              }
            >
              确认退回
            </Button>
          </Space>
        </Card>
      )}
    </Space>
  )
}
