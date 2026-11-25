import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  Button,
  Card,
  Col,
  Form,
  Input,
  List,
  Modal,
  Popconfirm,
  Row,
  Select,
  Space,
  Table,
  Tabs,
  Tag,
  Typography,
  message
} from 'antd'
import QueryState from '../components/QueryState'
import {
  api,
  exportApi,
  getErrorMessage,
  reviewApi,
  workspaceApi,
  type CreateWorkspacePayload,
  type InviteMemberPayload
} from '../lib/api'
import { useAuth } from '../lib/auth'
import { ROLE_META, formatDateTime } from '../lib/format'
import type { PendingCompletion, Role, WorkspaceType } from '../lib/types'

const ROLE_OPTIONS: Role[] = ['ADMIN', 'MEMBER', 'VIEWER']

interface InvitationView {
  workspaceId: string
  workspaceName: string
  workspaceType: WorkspaceType
  role: Role
  invitedAt: string
}

export default function Workspaces() {
  const queryClient = useQueryClient()
  const { workspaces, currentWorkspace, setCurrentWorkspaceId, refreshWorkspaces } = useAuth()
  const [createOpen, setCreateOpen] = useState(false)
  const [createForm] = Form.useForm<{ name: string }>()
  const [inviteForm] = Form.useForm<{ email: string; role: Role }>()
  const [rejectTarget, setRejectTarget] = useState<PendingCompletion | null>(null)
  const [rejectReason, setRejectReason] = useState('')
  const [exportId, setExportId] = useState<string | null>(null)
  const [exportOpen, setExportOpen] = useState(false)
  const [exportForm] = Form.useForm<{ password: string }>()

  const selectedId = currentWorkspace?.id

  const membersQuery = useQuery({
    queryKey: ['members', selectedId],
    queryFn: () => workspaceApi.members(selectedId!),
    enabled: !!selectedId
  })
  const invitesQuery = useQuery({
    queryKey: ['my-invitations'],
    queryFn: fetchInvitations
  })
  const reviewQuery = useQuery({
    queryKey: ['review', selectedId],
    queryFn: () => reviewApi.pending(selectedId!),
    enabled: !!selectedId
  })
  const exportsQuery = useQuery({
    queryKey: ['exports'],
    queryFn: () => exportApi.mine(),
    refetchInterval: (q) => {
      const data = q.state.data as Awaited<ReturnType<typeof exportApi.mine>> | undefined
      return data?.some((e) => e.state === 'PENDING' || e.state === 'RUNNING') ? 3000 : false
    }
  })

  const createMut = useMutation({
    mutationFn: (payload: CreateWorkspacePayload) => workspaceApi.create(payload),
    onSuccess: async (ws) => {
      message.success('空间已创建')
      await refreshWorkspaces()
      setCurrentWorkspaceId(ws.id)
      setCreateOpen(false)
      createForm.resetFields()
    },
    onError: (err) => message.error(getErrorMessage(err, '创建失败'))
  })

  const inviteMut = useMutation({
    mutationFn: (payload: InviteMemberPayload) => workspaceApi.invite(selectedId!, payload),
    onSuccess: () => {
      message.success('邀请已发送，等待对方接受')
      inviteForm.resetFields()
      void queryClient.invalidateQueries({ queryKey: ['members', selectedId] })
    },
    onError: (err) => message.error(getErrorMessage(err, '邀请失败'))
  })

  const roleMut = useMutation({
    mutationFn: ({ userId, role }: { userId: string; role: Role }) =>
      workspaceApi.changeRole(selectedId!, userId, role),
    onSuccess: () => {
      message.success('角色已更新')
      void queryClient.invalidateQueries({ queryKey: ['members', selectedId] })
    },
    onError: (err) => message.error(getErrorMessage(err, '更新角色失败'))
  })

  const removeMut = useMutation({
    mutationFn: (userId: string) => workspaceApi.remove(selectedId!, userId),
    onSuccess: () => {
      message.success('成员已移除')
      void queryClient.invalidateQueries({ queryKey: ['members', selectedId] })
    },
    onError: (err) => message.error(getErrorMessage(err, '移除失败'))
  })

  const acceptMut = useMutation({
    mutationFn: (workspaceId: string) => workspaceApi.accept(workspaceId),
    onSuccess: async () => {
      message.success('已加入空间')
      await refreshWorkspaces()
      void queryClient.invalidateQueries({ queryKey: ['my-invitations'] })
    },
    onError: (err) => message.error(getErrorMessage(err, '接受失败'))
  })

  const reviewMut = useMutation({
    mutationFn: ({ item, approve, reason }: { item: PendingCompletion; approve: boolean; reason?: string }) =>
      reviewApi.review(item.completionId, approve, reason),
    onSuccess: (_, vars) => {
      message.success(vars.approve ? '已通过核验，步骤完成' : '已退回，步骤回到执行中')
      setRejectTarget(null)
      setRejectReason('')
      void queryClient.invalidateQueries({ queryKey: ['review', selectedId] })
      void queryClient.invalidateQueries({ queryKey: ['tasks', selectedId] })
      void queryClient.invalidateQueries({ queryKey: ['today', selectedId] })
    },
    onError: (err) => message.error(getErrorMessage(err, '核验失败'))
  })

  const exportMut = useMutation({
    mutationFn: (password: string) => exportApi.create(selectedId!, password),
    onSuccess: (job) => {
      setExportId(job.id)
      setExportOpen(false)
      exportForm.resetFields()
      message.success('导出任务已创建，完成后可下载')
      void queryClient.invalidateQueries({ queryKey: ['exports'] })
    },
    onError: (err) => message.error(getErrorMessage(err, '创建导出失败'))
  })

  const submitExport = () => exportForm.submit()

  const downloadExport = async (id: string) => {
    try {
      const blob = await exportApi.download(id)
      const url = URL.createObjectURL(blob)
      const a = document.createElement('a')
      a.href = url
      a.download = `actionloop-export-${id}.json`
      a.click()
      URL.revokeObjectURL(url)
    } catch (err) {
      message.error(getErrorMessage(err, '下载失败，可能已过期'))
    }
  }

  const membersTab = (
    <Card title="成员与邀请">
      {currentWorkspace ? (
        <>
          <Form form={inviteForm} layout="inline" style={{ marginBottom: 16 }} onFinish={(v) => inviteMut.mutate(v)}>
            <Form.Item name="email" rules={[{ required: true, type: 'email', message: '有效邮箱' }]}>
              <Input placeholder="成员邮箱" />
            </Form.Item>
            <Form.Item name="role" initialValue="MEMBER">
              <Select style={{ width: 120 }} options={ROLE_OPTIONS.map((r) => ({ value: r, label: ROLE_META[r].label }))} />
            </Form.Item>
            <Form.Item>
              <Button type="primary" htmlType="submit" loading={inviteMut.isPending}>
                邀请
              </Button>
            </Form.Item>
          </Form>
          <List
            loading={membersQuery.isLoading}
            dataSource={membersQuery.data ?? []}
            locale={{ emptyText: '暂无成员' }}
            renderItem={(m) => (
              <List.Item
                actions={[
                  <Select
                    key="role"
                    size="small"
                    style={{ width: 110 }}
                    value={m.role}
                    disabled={m.role === 'OWNER'}
                    onChange={(role: Role) => roleMut.mutate({ userId: m.userId, role })}
                    options={(['OWNER', 'ADMIN', 'MEMBER', 'VIEWER'] as Role[]).map((r) => ({
                      value: r,
                      label: ROLE_META[r].label
                    }))}
                  />,
                  <Popconfirm
                    key="remove"
                    title="移除该成员？"
                    onConfirm={() => removeMut.mutate(m.userId)}
                    disabled={m.role === 'OWNER'}
                  >
                    <Button size="small" danger disabled={m.role === 'OWNER'}>
                      移除
                    </Button>
                  </Popconfirm>
                ]}
              >
                <List.Item.Meta
                  title={
                    <Space>
                      {m.displayName || m.username}
                      <Tag color={ROLE_META[m.role]?.color}>{ROLE_META[m.role]?.label}</Tag>
                      <Tag>{m.status}</Tag>
                    </Space>
                  }
                  description={`@${m.username}`}
                />
              </List.Item>
            )}
          />
        </>
      ) : (
        <Typography.Text type="secondary">请先选择或创建一个空间</Typography.Text>
      )}

      <Card size="small" title="我收到的邀请" style={{ marginTop: 16 }}>
        <List
          loading={invitesQuery.isLoading}
          dataSource={invitesQuery.data ?? []}
          locale={{ emptyText: '暂无邀请' }}
          renderItem={(inv) => (
            <List.Item
              actions={[
                <Button key="accept" type="primary" size="small" loading={acceptMut.isPending} onClick={() => acceptMut.mutate(inv.workspaceId)}>
                  接受
                </Button>,
                <Button
                  key="decline"
                  size="small"
                  onClick={() =>
                    void api
                      .post(`/invitations/${inv.workspaceId}/decline`)
                      .then(() => {
                        message.success('已拒绝邀请')
                        void queryClient.invalidateQueries({ queryKey: ['my-invitations'] })
                      })
                      .catch((err: unknown) => message.error(getErrorMessage(err, '拒绝失败')))
                  }
                >
                  拒绝
                </Button>
              ]}
            >
              <List.Item.Meta
                title={inv.workspaceName}
                description={`${inv.workspaceType === 'PERSONAL' ? '个人空间' : '团队空间'} · ${ROLE_META[inv.role]?.label} · ${formatDateTime(inv.invitedAt)}`}
              />
            </List.Item>
          )}
        />
      </Card>
    </Card>
  )

  const reviewTab = (
    <Card title="待核验完成凭证">
      <Typography.Paragraph type="secondary">
        只显示你有权核验的事项（负责人/经办人/空间管理员）。通过后步骤完成；退回后步骤回到执行中并记录原因。
      </Typography.Paragraph>
      <QueryState
        loading={reviewQuery.isLoading}
        error={reviewQuery.error}
        empty={(reviewQuery.data ?? []).length === 0}
        emptyText="没有待核验的凭证"
        onRetry={() => void reviewQuery.refetch()}
      >
        <Table
          rowKey="completionId"
          dataSource={reviewQuery.data ?? []}
          pagination={false}
          size="small"
          columns={[
            {
              title: '事项 / 步骤',
              render: (_, r: PendingCompletion) => (
                <Space direction="vertical" size={0}>
                  <Typography.Text strong>{r.taskTitle}</Typography.Text>
                  <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                    {r.stepTitle}
                  </Typography.Text>
                </Space>
              )
            },
            { title: '完成说明', dataIndex: 'statement', render: (v: string) => v || '—' },
            {
              title: '凭证',
              dataIndex: 'assetIds',
              render: (v: string[]) => (v.length ? <Tag>{v.length} 个文件</Tag> : <Tag>无附件</Tag>)
            },
            { title: '提交时间', dataIndex: 'submittedAt', render: (v: string) => formatDateTime(v) },
            {
              title: '操作',
              render: (_, r: PendingCompletion) => (
                <Space>
                  <Button size="small" type="primary" loading={reviewMut.isPending} onClick={() => reviewMut.mutate({ item: r, approve: true })}>
                    通过
                  </Button>
                  <Button size="small" danger onClick={() => setRejectTarget(r)}>
                    退回
                  </Button>
                </Space>
              )
            }
          ]}
        />
      </QueryState>

      {rejectTarget && (
        <Card size="small" title="退回原因" style={{ marginTop: 16 }}>
          <Input.TextArea
            rows={2}
            maxLength={500}
            value={rejectReason}
            onChange={(e) => setRejectReason(e.target.value)}
            placeholder="请说明需要补充或修正的内容"
          />
          <Space style={{ marginTop: 8 }}>
            <Button onClick={() => setRejectTarget(null)}>取消</Button>
            <Button
              danger
              type="primary"
              loading={reviewMut.isPending}
              onClick={() => reviewMut.mutate({ item: rejectTarget, approve: false, reason: rejectReason })}
            >
              确认退回
            </Button>
          </Space>
        </Card>
      )}
    </Card>
  )

  const settingsTab = (
    <Card title="数据与导出">
      <Space direction="vertical" style={{ width: '100%' }} size="middle">
        <Typography.Paragraph type="secondary">
          导出会包含你空间内可见的事项、步骤、材料、来源预览与审计索引，生成为 JSON 快照；
          文件默认 24 小时后过期并自动删除。高敏导出需要再次输入当前密码。
        </Typography.Paragraph>
        <Space>
          <Button type="primary" onClick={() => setExportOpen(true)} disabled={!selectedId}>
            创建空间导出
          </Button>
          <Button onClick={() => void exportsQuery.refetch()}>刷新</Button>
        </Space>
        <Table
          rowKey="id"
          size="small"
          loading={exportsQuery.isLoading}
          dataSource={exportsQuery.data ?? []}
          pagination={false}
          columns={[
            { title: '任务', dataIndex: 'id', render: (v: string) => v.slice(0, 8) + '…' },
            { title: '状态', dataIndex: 'state', render: (v: string) => <Tag color={v === 'READY' ? 'green' : v === 'FAILED' ? 'red' : 'blue'}>{v}</Tag> },
            { title: '大小', dataIndex: 'sizeBytes', render: (v: number | null) => (v ? `${(v / 1024).toFixed(1)} KB` : '—') },
            { title: '创建时间', dataIndex: 'createdAt', render: (v: string) => formatDateTime(v) },
            { title: '过期', dataIndex: 'expiresAt', render: (v: string | null) => formatDateTime(v) },
            {
              title: '操作',
              render: (_, r) => (
                <Space>
                  <Button
                    size="small"
                    disabled={r.state !== 'READY'}
                    onClick={() => void downloadExport(r.id)}
                  >
                    下载
                  </Button>
                  {exportId === r.id && <Tag color="blue">最新</Tag>}
                </Space>
              )
            }
          ]}
        />
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          删除来源后，下一次导出不再包含其原文；已生成的导出包不会自动重写，请在到期前重新导出。
        </Typography.Text>
      </Space>
    </Card>
  )

  return (
    <>
      <Row gutter={16}>
      <Col xs={24} lg={7}>
        <Card
          title="我的空间"
          extra={
            <Button size="small" type="primary" onClick={() => setCreateOpen((v) => !v)}>
              新建
            </Button>
          }
        >
          {createOpen && (
            <Form
              form={createForm}
              layout="inline"
              style={{ marginBottom: 12 }}
              onFinish={(v) => createMut.mutate({ name: v.name, type: 'TEAM' })}
            >
              <Form.Item name="name" rules={[{ required: true, message: '名称' }]}>
                <Input placeholder="空间名称" maxLength={64} />
              </Form.Item>
              <Form.Item>
                <Button type="primary" htmlType="submit" loading={createMut.isPending}>
                  创建
                </Button>
              </Form.Item>
            </Form>
          )}
          <List
            dataSource={workspaces}
            locale={{ emptyText: '暂无空间' }}
            renderItem={(ws) => (
              <List.Item
                actions={[
                  <Button key="open" type={ws.id === selectedId ? 'primary' : 'link'} onClick={() => setCurrentWorkspaceId(ws.id)}>
                    {ws.id === selectedId ? '当前' : '切换'}
                  </Button>
                ]}
              >
                <List.Item.Meta
                  title={
                    <Space>
                      {ws.name}
                      <Tag>{ws.type === 'PERSONAL' ? '个人' : '团队'}</Tag>
                    </Space>
                  }
                  description={
                    <Space>
                      <Tag color={ROLE_META[ws.role]?.color}>{ROLE_META[ws.role]?.label}</Tag>
                      <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                        加入于 {formatDateTime(ws.joinedAt)}
                      </Typography.Text>
                    </Space>
                  }
                />
              </List.Item>
            )}
          />
        </Card>
      </Col>
      <Col xs={24} lg={17}>
        <Tabs
          items={[
            { key: 'members', label: '成员与邀请', children: membersTab },
            { key: 'review', label: '完成核验', children: reviewTab },
            { key: 'settings', label: '数据与导出', children: settingsTab }
          ]}
        />
      </Col>
    </Row>
      <Modal
        title="创建空间导出"
        open={exportOpen}
        onCancel={() => {
          setExportOpen(false)
          exportForm.resetFields()
        }}
        onOk={() => void submitExport()}
        okText="确认导出"
        cancelText="取消"
        confirmLoading={exportMut.isPending}
        destroyOnClose
      >
        <Typography.Paragraph type="secondary" style={{ fontSize: 12 }}>
          导出包含当前空间内你可见的事项、步骤、材料、来源预览与审计索引，异步生成后可在任务列表下载。
          高敏导出需要再次输入当前密码。
        </Typography.Paragraph>
        <Form form={exportForm} layout="vertical" onFinish={(values) => exportMut.mutate(values.password)}>
          <Form.Item name="password" label="当前密码" rules={[{ required: true, message: '请输入当前密码' }]}>
            <Input.Password placeholder="当前密码" autoComplete="current-password" />
          </Form.Item>
        </Form>
      </Modal>
    </>
  )
}

async function fetchInvitations(): Promise<InvitationView[]> {
  const { data } = await api.get<{ data: InvitationView[] }>('/invitations')
  return data.data
}
