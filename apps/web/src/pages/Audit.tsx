import { useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import {
  Alert,
  Button,
  Card,
  Descriptions,
  Input,
  Modal,
  Select,
  Space,
  Table,
  Tag,
  Typography,
  message
} from 'antd'
import QueryState from '../components/QueryState'
import { accountApi, auditApi, authApi, exportApi, getErrorMessage } from '../lib/api'
import { useAuth } from '../lib/auth'
import { formatDateTime } from '../lib/format'
import type { AuditEntry } from '../lib/types'

const OBJECT_TYPES = [
  { value: 'TASK', label: '事项' },
  { value: 'TASK_STEP', label: '步骤' },
  { value: 'TASK_SOURCE', label: '事项来源' },
  { value: 'SOURCE_ITEM', label: '来源' },
  { value: 'CANDIDATE_TASK', label: '候选' },
  { value: 'MATERIAL', label: '材料' },
  { value: 'COMPLETION', label: '完成凭证' },
  { value: 'ASSIST_SESSION', label: '协助会话' },
  { value: 'MEMBERSHIP', label: '成员' },
  { value: 'AI_CALL', label: 'AI 调用' }
]

function resultColor(result: string): string {
  if (result === 'SUCCESS' || result === 'ALLOW') return 'green'
  if (result === 'DENIED' || result === 'FAIL') return 'red'
  return 'default'
}

export default function Audit() {
  const { currentWorkspace, user } = useAuth()
  const workspaceId = currentWorkspace?.id
  const queryClient = useQueryClient()
  const [objectType, setObjectType] = useState<string | undefined>(undefined)
  const [objectId, setObjectId] = useState('')
  const [cursor, setCursor] = useState<string | undefined>(undefined)
  const [deletionOpen, setDeletionOpen] = useState(false)
  const [deletionPassword, setDeletionPassword] = useState('')
  const [deletionBusy, setDeletionBusy] = useState(false)

  const meQuery = useQuery({
    queryKey: ['me'],
    queryFn: () => authApi.me()
  })
  const deletion = meQuery.data?.accountDeletion
  const deletionPending = deletion?.state === 'PENDING'

  const requestDeletion = async () => {
    setDeletionBusy(true)
    try {
      await accountApi.requestDeletion(deletionPassword)
      message.success('已进入注销冷却期，可随时取消')
      setDeletionOpen(false)
      setDeletionPassword('')
      await queryClient.invalidateQueries({ queryKey: ['me'] })
    } catch (err) {
      message.error(getErrorMessage(err, '申请注销失败'))
    } finally {
      setDeletionBusy(false)
    }
  }

  const cancelDeletion = async () => {
    setDeletionBusy(true)
    try {
      await accountApi.cancelDeletion()
      message.success('已取消账户注销')
      await queryClient.invalidateQueries({ queryKey: ['me'] })
    } catch (err) {
      message.error(getErrorMessage(err, '取消注销失败'))
    } finally {
      setDeletionBusy(false)
    }
  }

  const auditQuery = useQuery({
    queryKey: ['audit', workspaceId, objectType, objectId, cursor],
    queryFn: () =>
      auditApi.list(workspaceId!, {
        objectType,
        objectId: objectId.trim() || undefined,
        cursor,
        limit: 30
      }),
    enabled: !!workspaceId
  })

  const exportsQuery = useQuery({
    queryKey: ['exports'],
    queryFn: () => exportApi.mine()
  })

  const rows = auditQuery.data?.items ?? []

  return (
    <Space direction="vertical" style={{ width: '100%' }} size="middle">
      <Space direction="vertical" size={0}>
        <Typography.Title level={4} style={{ margin: 0 }}>
          审计与设置
        </Typography.Title>
        <Typography.Text type="secondary">
          权限变更、状态流转、AI 调用与安全拒绝均落审计；审计只追加，不因用户删除而消失。
        </Typography.Text>
      </Space>

      <Alert
        type="info"
        showIcon
        message="AI 只生成候选"
        description="所有 AI 调用记录包含模型、提示版本、输入来源与结果；AI 无法直接改变事项状态、权限与完成事实。无密钥环境使用确定性测试 Provider 时会明确标注为 demo。"
      />

      <Card size="small" title="当前环境">
        <Descriptions column={2} size="small">
          <Descriptions.Item label="登录用户">{user?.displayName ?? user?.username}</Descriptions.Item>
          <Descriptions.Item label="用户 ID">{user?.id}</Descriptions.Item>
          <Descriptions.Item label="当前空间">{currentWorkspace?.name ?? '—'}</Descriptions.Item>
          <Descriptions.Item label="我的角色">{currentWorkspace?.role ?? '—'}</Descriptions.Item>
          <Descriptions.Item label="时区">{user?.timezone}</Descriptions.Item>
          <Descriptions.Item label="语言">{user?.locale}</Descriptions.Item>
          <Descriptions.Item label="导出任务" span={2}>
            {(exportsQuery.data ?? []).length} 个（在“协同空间 → 数据与导出”中管理）
          </Descriptions.Item>
        </Descriptions>
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          设备与会话管理当前仅有服务端刷新令牌轮换与设备记录；面向用户的设备列表与远程注销界面尚未提供（见未完成项）。
        </Typography.Text>
      </Card>

      <Card
        size="small"
        title="账户注销"
        extra={
          <Tag color={deletionPending ? 'orange' : 'default'}>
            {deletionPending ? '冷却中' : '未申请'}
          </Tag>
        }
      >
        <Space direction="vertical" style={{ width: '100%' }} size="small">
          <Typography.Text type="secondary">
            注销需再次输入密码；提交后进入 {deletion?.cooldownDays ?? 7} 天冷却期，期间账户仍可使用且可随时取消。
            冷却结束后将删除你名下的个人空间、对象存储内容与设备会话，账户转为不可登录的匿名墓碑。
          </Typography.Text>
          {deletionPending ? (
            <>
              <Alert
                type="warning"
                showIcon
                message={`计划清除时间：${deletion?.scheduledAt ? formatDateTime(deletion.scheduledAt) : '—'}`}
                description="若你仍是含其他成员的共享空间所有者，需先转让所有权，否则到期不会清除并会记录拒绝审计。"
              />
              <Space>
                <Button danger loading={deletionBusy} onClick={() => void cancelDeletion()}>
                  取消注销
                </Button>
                <Button onClick={() => void meQuery.refetch()}>刷新状态</Button>
              </Space>
            </>
          ) : (
            <Space wrap>
              <Button danger onClick={() => setDeletionOpen(true)}>
                申请注销账户
              </Button>
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                建议先在“协同空间 → 数据与导出”导出需要保留的数据。
              </Typography.Text>
            </Space>
          )}
        </Space>
      </Card>

      <Modal
        title="申请注销账户"
        open={deletionOpen}
        okText="确认申请注销"
        cancelText="取消"
        okButtonProps={{ danger: true, disabled: !deletionPassword.trim(), loading: deletionBusy }}
        onOk={() => void requestDeletion()}
        onCancel={() => {
          if (deletionBusy) return
          setDeletionOpen(false)
          setDeletionPassword('')
        }}
      >
        <Space direction="vertical" style={{ width: '100%' }}>
          <Alert
            type="warning"
            showIcon
            message="注销不可逆"
            description={`提交后进入 ${deletion?.cooldownDays ?? 7} 天冷却期，期间可随时取消；冷却结束后个人空间与数据将被删除。`}
          />
          <Typography.Text>请输入当前密码以确认身份：</Typography.Text>
          <Input.Password
            placeholder="当前密码"
            autoComplete="current-password"
            value={deletionPassword}
            onChange={(e) => setDeletionPassword(e.target.value)}
          />
        </Space>
      </Modal>

      <Card
        size="small"
        title="审计日志"
        extra={
          <Space wrap>
            <Select
              allowClear
              placeholder="对象类型"
              style={{ width: 150 }}
              value={objectType}
              onChange={(v) => {
                setCursor(undefined)
                setObjectType(v)
              }}
              options={OBJECT_TYPES}
            />
            <Input.Search
              allowClear
              placeholder="对象 ID"
              style={{ width: 280 }}
              value={objectId}
              onChange={(e) => setObjectId(e.target.value)}
              onSearch={() => setCursor(undefined)}
            />
          </Space>
        }
      >
        <QueryState
          loading={auditQuery.isLoading}
          error={auditQuery.error}
          empty={rows.length === 0}
          emptyText="当前筛选条件下没有审计记录"
          onRetry={() => void auditQuery.refetch()}
        >
          <Table
            rowKey="id"
            size="small"
            dataSource={rows}
            pagination={false}
            columns={[
              { title: '时间', dataIndex: 'createdAt', width: 170, render: (v: string) => formatDateTime(v) },
              { title: '动作', dataIndex: 'action', render: (v: string) => <Typography.Text code>{v}</Typography.Text> },
              { title: '对象', render: (_, r: AuditEntry) => `${r.objectType ?? '—'} ${r.objectId ? r.objectId.slice(0, 8) + '…' : ''}` },
              { title: '结果', dataIndex: 'result', width: 100, render: (v: string) => <Tag color={resultColor(v)}>{v}</Tag> },
              {
                title: '详情',
                dataIndex: 'metadata',
                render: (v: string | null) => (
                  <Typography.Text type="secondary" style={{ fontSize: 12 }} ellipsis={{ tooltip: v ?? '' }}>
                    {v ?? '—'}
                  </Typography.Text>
                )
              }
            ]}
          />
          <Space style={{ width: '100%', justifyContent: 'space-between', marginTop: 12 }}>
            <Typography.Text type="secondary">请求 ID 便于对照服务端日志排障。</Typography.Text>
            <Space>
              <Tag>{rows.length} 条</Tag>
              <Button size="small" disabled={!cursor} onClick={() => setCursor(undefined)}>
                回到第一页
              </Button>
              <Button
                size="small"
                disabled={!auditQuery.data?.hasMore}
                onClick={() => setCursor(auditQuery.data?.nextCursor ?? undefined)}
              >
                下一页
              </Button>
            </Space>
          </Space>
        </QueryState>
      </Card>
    </Space>
  )
}
