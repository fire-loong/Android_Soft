import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import {
  Alert,
  Button,
  Card,
  Descriptions,
  Drawer,
  Empty,
  Input,
  List,
  Select,
  Space,
  Spin,
  Tag,
  Typography
} from 'antd'
import { useNavigate } from 'react-router-dom'
import EvidenceList from '../components/EvidenceList'
import QueryState from '../components/QueryState'
import { getErrorMessage, memoryApi } from '../lib/api'
import { useAuth } from '../lib/auth'
import { SOURCE_STATE_META, formatDateTime } from '../lib/format'
import type { MemoryHit } from '../lib/types'

const DOC_TYPE_OPTIONS = [
  { value: 'SCREENSHOT', label: '截图' },
  { value: 'FILE', label: '文件' },
  { value: 'NOTE', label: '备注' },
  { value: 'SOURCE', label: '来源' }
]

export default function Memory() {
  const navigate = useNavigate()
  const { currentWorkspace } = useAuth()
  const workspaceId = currentWorkspace?.id
  const [input, setInput] = useState('')
  const [submitted, setSubmitted] = useState('')
  const [type, setType] = useState<string | undefined>(undefined)
  const [selected, setSelected] = useState<MemoryHit | null>(null)

  const searchQuery = useQuery({
    queryKey: ['memory', workspaceId, submitted, type],
    queryFn: () => memoryApi.search(workspaceId!, submitted, type, undefined, 20),
    enabled: !!workspaceId && submitted.trim().length > 0
  })

  const contextQuery = useQuery({
    queryKey: ['memory-context', selected?.documentId, workspaceId],
    queryFn: () => memoryApi.context(selected!.documentId, workspaceId!),
    enabled: !!selected && !!workspaceId
  })

  const hits = searchQuery.data?.items ?? []

  return (
    <Space direction="vertical" style={{ width: '100%' }} size="middle">
      <Space direction="vertical" size={0}>
        <Typography.Title level={4} style={{ margin: 0 }}>
          个人记忆
        </Typography.Title>
        <Typography.Text type="secondary">
          找回截图、文件与通知原文；搜索先做权限过滤，结果必须带原文片段。
        </Typography.Text>
      </Space>

      <Card size="small">
        <Space.Compact style={{ width: '100%' }}>
          <Input.Search
            allowClear
            value={input}
            onChange={(e) => setInput(e.target.value)}
            onSearch={(v) => setSubmitted(v.trim())}
            placeholder="输入关键词，例如：助学金、成绩单、宿舍电费"
            enterButton="搜索"
            loading={searchQuery.isFetching}
          />
          <Select
            allowClear
            style={{ width: 160 }}
            placeholder="全部类型"
            value={type}
            onChange={(v) => setType(v)}
            options={DOC_TYPE_OPTIONS}
          />
        </Space.Compact>
      </Card>

      {!submitted ? (
        <Empty
          image={Empty.PRESENTED_IMAGE_SIMPLE}
          description="输入关键词开始检索；系统不会编造答案，无结果时请调整关键词或导入新资料。"
        />
      ) : (
        <QueryState
          loading={searchQuery.isLoading}
          error={searchQuery.error}
          empty={hits.length === 0}
          emptyText="没有匹配结果。可尝试更短的关键词、去掉类型筛选，或导入新资料。"
          onRetry={() => void searchQuery.refetch()}
        >
          <List
            dataSource={hits}
            renderItem={(h) => (
              <Card size="small" style={{ marginBottom: 10 }} onClick={() => setSelected(h)} hoverable>
                <Space direction="vertical" size={4} style={{ width: '100%' }}>
                  <Space wrap>
                    <Typography.Text strong>{h.title}</Typography.Text>
                    <Tag>{h.docType}</Tag>
                    <Tag color="blue">相关度 {h.score.toFixed(2)}</Tag>
                  </Space>
                  <Typography.Paragraph type="secondary" style={{ marginBottom: 0 }} ellipsis={{ rows: 2 }}>
                    {h.snippet}
                  </Typography.Paragraph>
                  <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                    导入于 {formatDateTime(h.createdAt)}
                    {h.privacyNote ? ` · ${h.privacyNote}` : ''}
                  </Typography.Text>
                </Space>
              </Card>
            )}
          />
        </QueryState>
      )}

      <Drawer
        title="情境重现"
        width={720}
        open={!!selected}
        onClose={() => setSelected(null)}
        extra={
          <Space>
            <Button onClick={() => navigate('/sources')}>来源管理</Button>
            <Button type="primary" onClick={() => navigate('/inbox')}>
              去收件箱
            </Button>
          </Space>
        }
      >
        {contextQuery.isLoading ? (
          <Spin />
        ) : contextQuery.error ? (
          <Alert type="error" showIcon message={getErrorMessage(contextQuery.error)} />
        ) : contextQuery.data ? (
          <Space direction="vertical" style={{ width: '100%' }} size="middle">
            <Space direction="vertical" size={0}>
              <Typography.Title level={5} style={{ margin: 0 }}>
                1. 原物与内容
              </Typography.Title>
              <Typography.Paragraph style={{ whiteSpace: 'pre-wrap', marginTop: 8 }}>
                {contextQuery.data.content ?? '（没有可展示的文本内容，可能仅保留原文件）'}
              </Typography.Paragraph>
            </Space>

            <Descriptions column={2} size="small" bordered title="2. 当时信息">
              <Descriptions.Item label="数据类型">{contextQuery.data.docType}</Descriptions.Item>
              <Descriptions.Item label="来源 App">{contextQuery.data.source?.sourceApp ?? '未记录'}</Descriptions.Item>
              <Descriptions.Item label="收到时间">
                {formatDateTime(contextQuery.data.source?.receivedAt ?? null)}
              </Descriptions.Item>
              <Descriptions.Item label="来源状态">
                {contextQuery.data.source ? (
                  <Tag color={SOURCE_STATE_META[contextQuery.data.source.sourceState]?.color}>
                    {SOURCE_STATE_META[contextQuery.data.source.sourceState]?.label}
                  </Tag>
                ) : (
                  '—'
                )}
              </Descriptions.Item>
              <Descriptions.Item label="原始链接" span={2}>
                {contextQuery.data.source?.linkUrl ? (
                  <Typography.Link href={contextQuery.data.source.linkUrl} target="_blank" rel="noreferrer">
                    {contextQuery.data.source.linkUrl}
                  </Typography.Link>
                ) : (
                  '来源未说明'
                )}
              </Descriptions.Item>
            </Descriptions>

            <div>
              <Typography.Title level={5}>3. 内容定位</Typography.Title>
              <EvidenceList evidence={contextQuery.data.evidence} workspaceId={workspaceId!} />
            </div>

            <div>
              <Typography.Title level={5}>4. 关联脉络</Typography.Title>
              {contextQuery.data.relatedTasks.length === 0 ? (
                <Typography.Text type="secondary">暂无关联事项</Typography.Text>
              ) : (
                <List
                  size="small"
                  dataSource={contextQuery.data.relatedTasks}
                  renderItem={(t) => (
                    <List.Item
                      actions={[
                        <Button key="go" size="small" onClick={() => navigate(`/tasks/${t.id}`)}>
                          打开
                        </Button>
                      ]}
                    >
                      {t.title}
                    </List.Item>
                  )}
                />
              )}
              {contextQuery.data.relatedNotes.length > 0 && (
                <List
                  size="small"
                  header={<Typography.Text type="secondary">相关备注</Typography.Text>}
                  dataSource={contextQuery.data.relatedNotes}
                  renderItem={(n) => <List.Item>{n}</List.Item>}
                />
              )}
            </div>

            <div>
              <Typography.Title level={5}>5. 依据与隐私</Typography.Title>
              <Alert
                type="info"
                showIcon
                message={contextQuery.data.privacyNote ?? '未记录额外隐私说明'}
                description="“当时为什么保存”只在有备注、关联事项或明确内容依据时呈现；没有证据时不会编造动机。删除原资料后，索引与分享链接将同步失效。"
              />
            </div>
          </Space>
        ) : (
          <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="无法读取情境" />
        )}
      </Drawer>
    </Space>
  )
}
