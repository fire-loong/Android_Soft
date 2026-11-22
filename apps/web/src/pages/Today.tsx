import { useMemo, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import type { Dayjs } from 'dayjs'
import {
  Alert,
  Button,
  Card,
  Col,
  DatePicker,
  Drawer,
  Empty,
  Form,
  Input,
  Row,
  Select,
  Space,
  Statistic,
  Table,
  Tag,
  Typography,
  message,
  type TableColumnsType
} from 'antd'
import { useNavigate } from 'react-router-dom'
import { getErrorMessage, inboxApi, reviewApi, taskApi, type CreateTaskPayload } from '../lib/api'
import { useAuth } from '../lib/auth'
import { TASK_STATUS_META, formatDateTime, isOverdue } from '../lib/format'
import type { Task, TaskStatus } from '../lib/types'

export default function Today() {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const { currentWorkspace } = useAuth()
  const [statusFilter, setStatusFilter] = useState<TaskStatus | undefined>(undefined)
  const [drawerOpen, setDrawerOpen] = useState(false)
  const [form] = Form.useForm<{
    title: string
    description?: string
    priority?: number
    status?: TaskStatus
    dueDate?: Dayjs
  }>()

  const workspaceId = currentWorkspace?.id

  const tasksQuery = useQuery({
    queryKey: ['tasks', workspaceId, statusFilter],
    queryFn: () => taskApi.list(workspaceId!, statusFilter, 0, 100),
    enabled: !!workspaceId,
    staleTime: 5_000
  })

  const inboxQuery = useQuery({
    queryKey: ['inbox', workspaceId, 'PENDING', 'today-badge'],
    queryFn: () => inboxApi.list(workspaceId!, 'PENDING', undefined, 50),
    enabled: !!workspaceId
  })

  const reviewQuery = useQuery({
    queryKey: ['review', workspaceId],
    queryFn: () => reviewApi.pending(workspaceId!),
    enabled: !!workspaceId
  })

  const stats = useMemo(() => {
    const all = tasksQuery.data?.content ?? []
    const now = Date.now()
    const day = 86_400_000
    return {
      total: all.length,
      active: all.filter((t) => t.status === 'ACTIVE' || t.status === 'IN_PROGRESS').length,
      overdue: all.filter((t) => isOverdue(t.dueAt, t.status)).length,
      soon: all.filter(
        (t) =>
          t.dueAt &&
          !isOverdue(t.dueAt, t.status) &&
          new Date(t.dueAt).getTime() - now < day &&
          t.status !== 'COMPLETED' &&
          t.status !== 'CANCELLED'
      ).length
    }
  }, [tasksQuery.data])

  const createMut = useMutation({
    mutationFn: (payload: CreateTaskPayload) => taskApi.create(payload),
    onSuccess: () => {
      message.success('事项已创建')
      void queryClient.invalidateQueries({ queryKey: ['tasks', workspaceId] })
      setDrawerOpen(false)
      form.resetFields()
    },
    onError: (err) => message.error(getErrorMessage(err, '创建失败'))
  })

  const columns = useMemo<TableColumnsType<Task>>(
    () => [
      {
        title: '事项',
        dataIndex: 'title',
        key: 'title',
        render: (text: string, record) => (
          <Space direction="vertical" size={0}>
            <Typography.Text strong>{text}</Typography.Text>
            {record.description && (
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                {record.description.slice(0, 60)}
              </Typography.Text>
            )}
          </Space>
        )
      },
      {
        title: '状态',
        dataIndex: 'status',
        key: 'status',
        width: 120,
        render: (status: TaskStatus) => (
          <Tag color={TASK_STATUS_META[status]?.color}>{TASK_STATUS_META[status]?.label ?? status}</Tag>
        )
      },
      {
        title: '截止',
        dataIndex: 'dueAt',
        key: 'dueAt',
        width: 180,
        render: (dueAt: string | null, record) =>
          dueAt ? (
            <Typography.Text type={isOverdue(dueAt, record.status) ? 'danger' : undefined}>
              {formatDateTime(dueAt)}
            </Typography.Text>
          ) : (
            '—'
          )
      },
      {
        title: '优先级',
        dataIndex: 'priority',
        key: 'priority',
        width: 90,
        render: (p: number | null) => (p == null ? '—' : String(p))
      },
      {
        title: '更新时间',
        dataIndex: 'updatedAt',
        key: 'updatedAt',
        width: 180,
        render: (t: string) => formatDateTime(t)
      }
    ],
    []
  )

  if (!currentWorkspace) {
    return (
      <Empty
        description="还没有可用空间"
        image={Empty.PRESENTED_IMAGE_SIMPLE}
      >
        <Button type="primary" onClick={() => navigate('/workspaces')}>
          去创建空间
        </Button>
      </Empty>
    )
  }

  return (
    <Space direction="vertical" style={{ width: '100%' }} size="middle">
      <Space style={{ width: '100%', justifyContent: 'space-between' }}>
        <Typography.Title level={4} style={{ margin: 0 }}>
          {currentWorkspace.name} · 事项
        </Typography.Title>
        <Space>
          <Select
            allowClear
            style={{ width: 140 }}
            placeholder="全部状态"
            value={statusFilter}
            onChange={(v) => setStatusFilter(v)}
            options={(Object.keys(TASK_STATUS_META) as TaskStatus[]).map((s) => ({
              value: s,
              label: TASK_STATUS_META[s].label
            }))}
          />
          <Button type="primary" onClick={() => setDrawerOpen(true)}>
            新建事项
          </Button>
        </Space>
      </Space>

      <Row gutter={12}>
        <Col xs={12} sm={6}>
          <Card size="small" hoverable onClick={() => navigate('/inbox')}>
            <Statistic
              title="待确认候选"
              value={inboxQuery.data?.items.length ?? 0}
              suffix={inboxQuery.data?.hasMore ? '+' : ''}
            />
          </Card>
        </Col>
        <Col xs={12} sm={6}>
          <Card size="small">
            <Statistic title="进行中事项" value={stats.active} />
          </Card>
        </Col>
        <Col xs={12} sm={6}>
          <Card size="small">
            <Statistic title="24 小时内到期" value={stats.soon} valueStyle={{ color: '#fa8c16' }} />
          </Card>
        </Col>
        <Col xs={12} sm={6}>
          <Card size="small" hoverable onClick={() => navigate('/workspaces')}>
            <Statistic
              title="待核验凭证"
              value={reviewQuery.data?.length ?? 0}
              valueStyle={{ color: (reviewQuery.data?.length ?? 0) > 0 ? '#cf1322' : undefined }}
            />
          </Card>
        </Col>
      </Row>

      {stats.overdue > 0 && (
        <Alert
          type="warning"
          showIcon
          message={`有 ${stats.overdue} 个事项已逾期`}
          description="逾期只做展示提示，不会自动改变状态或创建新提醒。"
        />
      )}

      <Table<Task>
        rowKey="id"
        loading={tasksQuery.isLoading}
        dataSource={tasksQuery.data?.content ?? []}
        columns={columns}
        pagination={false}
        onRow={(record) => ({
          onClick: () => navigate(`/tasks/${record.id}`),
          style: { cursor: 'pointer' }
        })}
        locale={{ emptyText: '暂无事项，点击右上角新建' }}
      />

      <Drawer
        title="新建事项"
        open={drawerOpen}
        onClose={() => setDrawerOpen(false)}
        width={420}
        extra={
          <Button
            type="primary"
            loading={createMut.isPending}
            onClick={() => form.submit()}
          >
            创建
          </Button>
        }
      >
        <Form
          form={form}
          layout="vertical"
          onFinish={(v) => {
            if (!workspaceId) return
            const payload: CreateTaskPayload = {
              workspaceId,
              title: v.title,
              description: v.description,
              priority: v.priority,
              status: v.status ?? 'ACTIVE',
              dueDate: v.dueDate ? v.dueDate.format('YYYY-MM-DD') : undefined
            }
            createMut.mutate(payload)
          }}
        >
          <Form.Item
            label="标题"
            name="title"
            rules={[{ required: true, message: '请输入标题' }, { max: 128 }]}
          >
            <Input placeholder="要完成的事项" maxLength={128} />
          </Form.Item>
          <Form.Item label="描述" name="description">
            <Input.TextArea rows={3} maxLength={4000} placeholder="选填" />
          </Form.Item>
          <Form.Item label="截止日期" name="dueDate">
            <DatePicker style={{ width: '100%' }} format="YYYY-MM-DD" />
          </Form.Item>
          <Form.Item label="优先级" name="priority">
            <Select
              allowClear
              placeholder="选填"
              options={[
                { value: 0, label: '0 - 低' },
                { value: 1, label: '1 - 普通' },
                { value: 2, label: '2 - 高' },
                { value: 3, label: '3 - 紧急' }
              ]}
            />
          </Form.Item>
          <Form.Item label="初始状态" name="status" initialValue="ACTIVE">
            <Select
              options={(['DRAFT', 'PENDING_CONFIRMATION', 'ACTIVE'] as TaskStatus[]).map((s) => ({
                value: s,
                label: TASK_STATUS_META[s].label
              }))}
            />
          </Form.Item>
        </Form>
      </Drawer>
    </Space>
  )
}
