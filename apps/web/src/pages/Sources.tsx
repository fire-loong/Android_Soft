import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  Alert,
  Button,
  Card,
  Descriptions,
  Drawer,
  Empty,
  Form,
  Input,
  List,
  Modal,
  Popconfirm,
  Select,
  Space,
  Spin,
  Tag,
  Typography,
  Upload,
  message
} from 'antd'
import type { UploadFile } from 'antd'
import EvidenceList from '../components/EvidenceList'
import QueryState from '../components/QueryState'
import { assetApi, getErrorMessage, sourceApi } from '../lib/api'
import { useAuth } from '../lib/auth'
import { SOURCE_STATE_META, formatDateTime } from '../lib/format'
import type { SourceItem, SourceType } from '../lib/types'

const SOURCE_TYPE_OPTIONS: Array<{ value: SourceType; label: string }> = [
  { value: 'LINK', label: '网页链接' },
  { value: 'SHARE', label: '系统分享文本' },
  { value: 'NOTIFICATION', label: '通知摘要' },
  { value: 'SCREENSHOT', label: '截图' },
  { value: 'FILE', label: '文件' },
  { value: 'USER_STATEMENT', label: '口述/手工记录' },
  { value: 'OFFICIAL', label: '官方来源' }
]

export default function Sources() {
  const queryClient = useQueryClient()
  const { currentWorkspace } = useAuth()
  const workspaceId = currentWorkspace?.id
  const [page, setPage] = useState(0)
  const [detail, setDetail] = useState<SourceItem | null>(null)
  const [addOpen, setAddOpen] = useState(false)
  const [fileList, setFileList] = useState<UploadFile[]>([])
  const [submitting, setSubmitting] = useState(false)
  const [form] = Form.useForm<{
    sourceType: SourceType
    title?: string
    text: string
    linkUrl?: string
  }>()

  const listQuery = useQuery({
    queryKey: ['sources', workspaceId, page],
    queryFn: () => sourceApi.list(workspaceId!, page, 20),
    enabled: !!workspaceId
  })

  const detailQuery = useQuery({
    queryKey: ['source', detail?.id, workspaceId],
    queryFn: () => sourceApi.get(detail!.id, workspaceId!),
    enabled: !!detail && !!workspaceId
  })

  const recheckMut = useMutation({
    mutationFn: (sourceId: string) => sourceApi.recheck(sourceId, workspaceId!),
    onSuccess: (r) => {
      if (r.changed) {
        message.warning('来源内容已变化，请重新核对引用与待办字段')
      } else {
        message.success('来源复查完成，内容未变化')
      }
      void queryClient.invalidateQueries({ queryKey: ['sources', workspaceId] })
      void queryClient.invalidateQueries({ queryKey: ['source', detail?.id, workspaceId] })
    },
    onError: (err) => message.error(getErrorMessage(err, '复查失败'))
  })

  const deleteMut = useMutation({
    mutationFn: (source: SourceItem) => sourceApi.remove(source.id, workspaceId!, source.version),
    onSuccess: () => {
      message.success('来源已删除，搜索与分享将不再返回该内容')
      setDetail(null)
      void queryClient.invalidateQueries({ queryKey: ['sources', workspaceId] })
    },
    onError: (err) => message.error(getErrorMessage(err, '删除失败'))
  })

  const submitNew = async () => {
    if (!workspaceId) return
    const v = await form.validateFields()
    setSubmitting(true)
    try {
      let assetId: string | undefined
      let text = v.text
      if (fileList.length > 0) {
        const file = fileList[0].originFileObj as File | undefined
        if (file) {
          const asset = await assetApi.upload(workspaceId, file)
          assetId = asset.id
          if (!text || !text.trim()) {
            text = `已导入文件：${file.name}`
          }
        }
      }
      await sourceApi.share({
        workspaceId,
        sourceType: v.sourceType,
        sourceApp: v.sourceType === 'LINK' ? 'web' : 'web-manual',
        sourceKey: `web-${Date.now()}`,
        title: v.title,
        text,
        linkUrl: v.linkUrl,
        assetId,
        timezone: Intl.DateTimeFormat().resolvedOptions().timeZone
      })
      message.success('来源已导入，规则已生成候选（需在收件箱确认）')
      setAddOpen(false)
      form.resetFields()
      setFileList([])
      void queryClient.invalidateQueries({ queryKey: ['sources', workspaceId] })
      void queryClient.invalidateQueries({ queryKey: ['inbox', workspaceId] })
    } catch (err) {
      message.error(getErrorMessage(err, '导入失败'))
    } finally {
      setSubmitting(false)
    }
  }

  const rows = listQuery.data?.content ?? []

  return (
    <Space direction="vertical" style={{ width: '100%' }} size="middle">
      <Space style={{ width: '100%', justifyContent: 'space-between', flexWrap: 'wrap' }}>
        <Space direction="vertical" size={0}>
          <Typography.Title level={4} style={{ margin: 0 }}>
            来源管理
          </Typography.Title>
          <Typography.Text type="secondary">
            每个事项都能回溯到原文；官方来源需经复查，社区内容不会冒充官方政策。
          </Typography.Text>
        </Space>
        <Button type="primary" onClick={() => setAddOpen(true)}>
          导入来源
        </Button>
      </Space>

      <QueryState
        loading={listQuery.isLoading}
        error={listQuery.error}
        empty={rows.length === 0}
        emptyText="还没有来源。可从 Android 分享，或在本页导入链接、文本与文件。"
        onRetry={() => void listQuery.refetch()}
      >
        <List
          dataSource={rows}
          renderItem={(s) => (
            <List.Item
              actions={[
                <Button key="open" size="small" onClick={() => setDetail(s)}>
                  查看
                </Button>
              ]}
            >
              <List.Item.Meta
                title={
                  <Space wrap>
                    <Typography.Text strong>{s.sourceTitle ?? s.sourceApp ?? '未命名来源'}</Typography.Text>
                    <Tag color={SOURCE_STATE_META[s.sourceState]?.color}>
                      {SOURCE_STATE_META[s.sourceState]?.label}
                    </Tag>
                    <Tag>{s.sourceType}</Tag>
                    <Tag>{s.privacyLevel}</Tag>
                  </Space>
                }
                description={
                  <Space direction="vertical" size={2} style={{ width: '100%' }}>
                    <Typography.Text type="secondary" ellipsis style={{ maxWidth: 720 }}>
                      {s.preview ?? '（无预览）'}
                    </Typography.Text>
                    <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                      收到 {formatDateTime(s.receivedAt)} · 证据 {s.evidence.length} 条 · 候选 {s.candidateIds.length} 个
                      {s.lastCheckedAt ? ` · 最近复查 ${formatDateTime(s.lastCheckedAt)}` : ''}
                    </Typography.Text>
                  </Space>
                }
              />
            </List.Item>
          )}
        />
        <Space style={{ width: '100%', justifyContent: 'space-between' }}>
          <Button disabled={page === 0} onClick={() => setPage((p) => Math.max(0, p - 1))}>
            上一页
          </Button>
          <Typography.Text type="secondary">
            第 {page + 1} / {Math.max(1, listQuery.data?.totalPages ?? 1)} 页
          </Typography.Text>
          <Button
            disabled={!listQuery.data || page + 1 >= listQuery.data.totalPages}
            onClick={() => setPage((p) => p + 1)}
          >
            下一页
          </Button>
        </Space>
      </QueryState>

      <Drawer
        title="来源详情"
        width={680}
        open={!!detail}
        onClose={() => setDetail(null)}
        extra={
          detail && (
            <Space>
              <Button loading={recheckMut.isPending} onClick={() => recheckMut.mutate(detail.id)}>
                复查来源
              </Button>
              <Popconfirm
                title="删除该来源？"
                description="删除后搜索、分享和事项证据将不再返回该内容（软删除 30 天后清理）。"
                okText="删除"
                okButtonProps={{ danger: true }}
                onConfirm={() => deleteMut.mutate(detail)}
              >
                <Button danger loading={deleteMut.isPending}>
                  删除
                </Button>
              </Popconfirm>
            </Space>
          )
        }
      >
        {detailQuery.isLoading ? (
          <Spin />
        ) : detailQuery.error ? (
          <Alert type="error" showIcon message={getErrorMessage(detailQuery.error)} />
        ) : detailQuery.data ? (
          <Space direction="vertical" style={{ width: '100%' }} size="middle">
            <Descriptions column={1} size="small" bordered>
              <Descriptions.Item label="来源类型">{detailQuery.data.sourceType}</Descriptions.Item>
              <Descriptions.Item label="来源 App">{detailQuery.data.sourceApp ?? '未记录'}</Descriptions.Item>
              <Descriptions.Item label="可信状态">
                <Tag color={SOURCE_STATE_META[detailQuery.data.sourceState]?.color}>
                  {SOURCE_STATE_META[detailQuery.data.sourceState]?.label}
                </Tag>
              </Descriptions.Item>
              <Descriptions.Item label="链接">
                {detailQuery.data.linkUrl ? (
                  <Typography.Link href={detailQuery.data.linkUrl} target="_blank" rel="noreferrer">
                    {detailQuery.data.linkUrl}
                  </Typography.Link>
                ) : (
                  '来源未说明'
                )}
              </Descriptions.Item>
              <Descriptions.Item label="内容哈希">{detailQuery.data.contentHash.slice(0, 16)}…</Descriptions.Item>
              <Descriptions.Item label="原文预览">
                <Typography.Paragraph style={{ whiteSpace: 'pre-wrap', marginBottom: 0 }}>
                  {detailQuery.data.preview ?? '—'}
                </Typography.Paragraph>
              </Descriptions.Item>
            </Descriptions>
            <Typography.Title level={5}>证据引用</Typography.Title>
            <EvidenceList evidence={detailQuery.data.evidence} workspaceId={workspaceId!} />
          </Space>
        ) : (
          <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="来源不可用" />
        )}
      </Drawer>

      <Modal
        title="导入来源"
        open={addOpen}
        onCancel={() => setAddOpen(false)}
        onOk={() => void submitNew()}
        okText="导入并生成候选"
        confirmLoading={submitting}
        width={620}
        destroyOnHidden
      >
        <Alert
          type="info"
          showIcon
          style={{ marginBottom: 12 }}
          message="导入只创建来源与候选，不会自动创建事项或提醒。"
          description="系统不会读取微信、QQ、短信等应用的私有数据库；请通过用户主动分享或文件选择导入。"
        />
        <Form form={form} layout="vertical" initialValues={{ sourceType: 'SHARE' }}>
          <Form.Item label="来源类型" name="sourceType" rules={[{ required: true }]}>
            <Select options={SOURCE_TYPE_OPTIONS} />
          </Form.Item>
          <Form.Item label="标题（选填）" name="title">
            <Input maxLength={256} placeholder="例如：助学金申请通知" />
          </Form.Item>
          <Form.Item label="原文内容" name="text" rules={[{ required: true, message: '请粘贴原文或描述' }]}>
            <Input.TextArea rows={5} maxLength={20000} placeholder="粘贴通知/分享文本；不要填入密码、验证码、银行卡号等敏感信息" />
          </Form.Item>
          <Form.Item label="原始链接（选填）" name="linkUrl">
            <Input maxLength={2048} placeholder="https://" />
          </Form.Item>
          <Form.Item label="附件（选填，同哈希文件会自动去重）">
            <Upload
              fileList={fileList}
              maxCount={1}
              beforeUpload={() => false}
              onChange={({ fileList: fl }) => setFileList(fl)}
            >
              <Button>选择文件</Button>
            </Upload>
          </Form.Item>
        </Form>
      </Modal>
    </Space>
  )
}
