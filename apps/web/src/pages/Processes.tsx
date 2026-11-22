import { useEffect, useMemo, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import type { Dayjs } from 'dayjs'
import {
  Alert,
  Button,
  Card,
  Col,
  DatePicker,
  Descriptions,
  Divider,
  Empty,
  Form,
  Input,
  List,
  Modal,
  Row,
  Select,
  Space,
  Spin,
  Switch,
  Tag,
  Typography,
  message
} from 'antd'
import { useNavigate } from 'react-router-dom'
import QueryState from '../components/QueryState'
import { getErrorMessage, processApi, sourceApi, type ProcessStepInput } from '../lib/api'
import { useAuth } from '../lib/auth'
import { PROCESS_REVIEW_META, formatDateTime } from '../lib/format'
import type { ProcessVersion } from '../lib/types'

export default function Processes() {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const { currentWorkspace } = useAuth()
  const workspaceId = currentWorkspace?.id
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [versionId, setVersionId] = useState<string | null>(null)
  const [createOpen, setCreateOpen] = useState(false)
  const [createForm] = Form.useForm<{ title: string; regionCode?: string }>()
  const [draftSteps, setDraftSteps] = useState<ProcessStepInput[]>([])
  const [activeStep, setActiveStep] = useState<number | null>(null)
  const [publishOpen, setPublishOpen] = useState(false)
  const [instantiateOpen, setInstantiateOpen] = useState(false)
  const [publishForm] = Form.useForm<{ allowPersonalUnverified: boolean; reviewerNote?: string }>()
  const [instantiateForm] = Form.useForm<{ title?: string; dueAt?: Dayjs }>()

  const templatesQuery = useQuery({
    queryKey: ['processes', workspaceId],
    queryFn: () => processApi.list(workspaceId!, undefined, 50),
    enabled: !!workspaceId
  })

  const selectedQuery = useQuery({
    queryKey: ['process', selectedId, workspaceId],
    queryFn: () => processApi.get(selectedId!, workspaceId!),
    enabled: !!selectedId && !!workspaceId
  })

  const sourcesQuery = useQuery({
    queryKey: ['sources', workspaceId, 'evidence-pool'],
    queryFn: () => sourceApi.list(workspaceId!, 0, 100),
    enabled: !!workspaceId
  })

  const template = selectedQuery.data
  const versions = useMemo(
    () => [...(template?.versions ?? [])].sort((a, b) => b.versionNo - a.versionNo),
    [template]
  )
  const version: ProcessVersion | null = useMemo(
    () => versions.find((v) => v.id === versionId) ?? versions[0] ?? null,
    [versions, versionId]
  )

  useEffect(() => {
    setVersionId(null)
    setActiveStep(null)
  }, [selectedId])

  useEffect(() => {
    if (!version) {
      setDraftSteps([])
      return
    }
    setDraftSteps(
      version.steps.map((s) => ({
        instruction: s.instruction,
        required: s.required ?? true,
        officialEntryUrl: s.officialEntryUrl ?? undefined,
        sourceRefIds: s.sourceRefIds
      }))
    )
    setActiveStep(version.steps.length > 0 ? 0 : null)
  }, [version?.id])

  const evidenceOptions = useMemo(() => {
    const opts: Array<{ value: string; label: string }> = []
    for (const src of sourcesQuery.data?.content ?? []) {
      for (const e of src.evidence) {
        opts.push({
          value: e.id,
          label: `${src.sourceTitle ?? src.sourceApp ?? '来源'}：${(e.quote ?? '无引文').slice(0, 30)}`
        })
      }
    }
    return opts
  }, [sourcesQuery.data])

  const createMut = useMutation({
    mutationFn: (v: { title: string; regionCode?: string }) =>
      processApi.create({ workspaceId: workspaceId!, title: v.title, regionCode: v.regionCode }),
    onSuccess: (t) => {
      message.success('流程模板已创建')
      setCreateOpen(false)
      createForm.resetFields()
      setSelectedId(t.id)
      void queryClient.invalidateQueries({ queryKey: ['processes', workspaceId] })
    },
    onError: (err) => message.error(getErrorMessage(err, '创建失败'))
  })

  const createVersionMut = useMutation({
    mutationFn: () => processApi.createVersion(template!.id, []),
    onSuccess: (v) => {
      message.success(`已创建草稿版本 v${v.versionNo}`)
      setVersionId(v.id)
      void queryClient.invalidateQueries({ queryKey: ['process', selectedId, workspaceId] })
      void queryClient.invalidateQueries({ queryKey: ['processes', workspaceId] })
    },
    onError: (err) => message.error(getErrorMessage(err, '创建版本失败'))
  })

  const saveMut = useMutation({
    mutationFn: () => processApi.replaceSteps(version!.id, draftSteps.filter((s) => s.instruction.trim())),
    onSuccess: () => {
      message.success('步骤草稿已保存')
      void queryClient.invalidateQueries({ queryKey: ['process', selectedId, workspaceId] })
    },
    onError: (err) => message.error(getErrorMessage(err, '保存失败'))
  })

  const publishMut = useMutation({
    mutationFn: (v: { allowPersonalUnverified: boolean; reviewerNote?: string }) =>
      processApi.publish(version!.id, v),
    onSuccess: (v) => {
      message.success(
        v.reviewState === 'VERIFIED' ? '已核验发布' : '已按“个人未核验”发布，客户端会显著标注'
      )
      setPublishOpen(false)
      void queryClient.invalidateQueries({ queryKey: ['process', selectedId, workspaceId] })
      void queryClient.invalidateQueries({ queryKey: ['processes', workspaceId] })
    },
    onError: (err) => message.error(getErrorMessage(err, '发布失败'))
  })

  const instantiateMut = useMutation({
    mutationFn: (v: { title?: string; dueAt?: Dayjs }) =>
      processApi.instantiate(version!.id, {
        workspaceId: workspaceId!,
        title: v.title || template?.title,
        dueAt: v.dueAt ? v.dueAt.toISOString() : undefined,
        dueTimezone: Intl.DateTimeFormat().resolvedOptions().timeZone
      }),
    onSuccess: (task) => {
      message.success('已实例化为事项，步骤已固化到当前版本')
      setInstantiateOpen(false)
      void queryClient.invalidateQueries({ queryKey: ['tasks', workspaceId] })
      navigate(`/tasks/${task.id}`)
    },
    onError: (err) => message.error(getErrorMessage(err, '实例化失败'))
  })

  const editable = version?.reviewState === 'DRAFT'

  const updateStep = (index: number, patch: Partial<ProcessStepInput>) => {
    setDraftSteps((prev) => prev.map((s, i) => (i === index ? { ...s, ...patch } : s)))
  }

  const moveStep = (index: number, delta: number) => {
    setDraftSteps((prev) => {
      const next = [...prev]
      const target = index + delta
      if (target < 0 || target >= next.length) return prev
      const tmp = next[index]
      next[index] = next[target]
      next[target] = tmp
      return next
    })
    setActiveStep((i) => (i == null ? i : Math.max(0, Math.min(draftSteps.length - 1, i + delta))))
  }

  return (
    <Space direction="vertical" style={{ width: '100%' }} size="middle">
      <Space style={{ width: '100%', justifyContent: 'space-between', flexWrap: 'wrap' }}>
        <Space direction="vertical" size={0}>
          <Typography.Title level={4} style={{ margin: 0 }}>
            流程工作室
          </Typography.Title>
          <Typography.Text type="secondary">
            模板 → 草稿版本 → 来源引用 → 复核发布 → 实例化。已发布版本不可原地修改。
          </Typography.Text>
        </Space>
        <Button type="primary" onClick={() => setCreateOpen(true)}>
          新建流程模板
        </Button>
      </Space>

      <Row gutter={16}>
        <Col xs={24} md={7}>
          <Card size="small" title="流程模板">
            <QueryState
              loading={templatesQuery.isLoading}
              error={templatesQuery.error}
              empty={(templatesQuery.data?.items.length ?? 0) === 0}
              emptyText="还没有流程模板，先新建一个"
              onRetry={() => void templatesQuery.refetch()}
            >
              <List
                size="small"
                dataSource={templatesQuery.data?.items ?? []}
                renderItem={(t) => (
                  <List.Item
                    onClick={() => setSelectedId(t.id)}
                    style={{
                      cursor: 'pointer',
                      background: selectedId === t.id ? '#e6f4ff' : undefined,
                      borderRadius: 6,
                      paddingInline: 8
                    }}
                  >
                    <List.Item.Meta
                      title={t.title}
                      description={
                        <Space size={4} wrap>
                          {t.regionCode && <Tag>{t.regionCode}</Tag>}
                          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                            {t.versions.length} 个版本
                          </Typography.Text>
                        </Space>
                      }
                    />
                  </List.Item>
                )}
              />
            </QueryState>
          </Card>
        </Col>

        <Col xs={24} md={17}>
          {!selectedId ? (
            <Card>
              <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="从左侧选择流程模板，或新建一个" />
            </Card>
          ) : selectedQuery.isLoading ? (
            <Spin />
          ) : selectedQuery.error ? (
            <Alert type="error" showIcon message={getErrorMessage(selectedQuery.error)} />
          ) : template ? (
            <Space direction="vertical" style={{ width: '100%' }} size="middle">
              <Card
                size="small"
                title={
                  <Space wrap>
                    <Typography.Text strong>{template.title}</Typography.Text>
                    {template.regionCode && <Tag>地区 {template.regionCode}</Tag>}
                  </Space>
                }
                extra={
                  <Space wrap>
                    <Select
                      style={{ width: 220 }}
                      value={version?.id}
                      onChange={(v) => setVersionId(v)}
                      options={versions.map((v) => ({
                        value: v.id,
                        label: `v${v.versionNo} · ${PROCESS_REVIEW_META[v.reviewState]?.label ?? v.reviewState}`
                      }))}
                    />
                    <Button loading={createVersionMut.isPending} onClick={() => createVersionMut.mutate()}>
                      新建草稿版本
                    </Button>
                  </Space>
                }
              >
                {version ? (
                  <Space direction="vertical" style={{ width: '100%' }} size={8}>
                    <Space wrap>
                      <Tag color={PROCESS_REVIEW_META[version.reviewState]?.color}>
                        {PROCESS_REVIEW_META[version.reviewState]?.label}
                      </Tag>
                      {version.publishedAt && (
                        <Typography.Text type="secondary">
                          发布于 {formatDateTime(version.publishedAt)}
                        </Typography.Text>
                      )}
                      {version.sourceSnapshotHash && (
                        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                          来源快照 {version.sourceSnapshotHash.slice(0, 12)}…
                        </Typography.Text>
                      )}
                    </Space>
                    {(version.reviewState === 'PERSONAL_UNVERIFIED' || version.reviewState === 'DRAFT') && (
                      <Alert
                        type="warning"
                        showIcon
                        message={version.reviewState === 'DRAFT' ? '草稿版本' : '个人未核验流程'}
                        description="未通过双人复核的流程不会标记为官方；客户端会显示“非官方来源”，请按官方页面核对。"
                      />
                    )}
                  </Space>
                ) : (
                  <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="该模板还没有版本" />
                )}
              </Card>

              {version && (
                <Row gutter={16}>
                  <Col xs={24} lg={14}>
                    <Card
                      size="small"
                      title="步骤（拖动按钮调整顺序只改变 order）"
                      extra={
                        <Space>
                          <Button
                            size="small"
                            disabled={!editable}
                            onClick={() =>
                              setDraftSteps((prev) => [
                                ...prev,
                                { instruction: '', required: true, sourceRefIds: [] }
                              ])
                            }
                          >
                            添加步骤
                          </Button>
                          <Button
                            size="small"
                            type="primary"
                            disabled={!editable}
                            loading={saveMut.isPending}
                            onClick={() => saveMut.mutate()}
                          >
                            保存草稿
                          </Button>
                        </Space>
                      }
                    >
                      {draftSteps.length === 0 ? (
                        <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="还没有步骤" />
                      ) : (
                        <List
                          size="small"
                          dataSource={draftSteps}
                          renderItem={(s, index) => (
                            <List.Item
                              onClick={() => setActiveStep(index)}
                              style={{
                                cursor: 'pointer',
                                background: activeStep === index ? '#f0f7ff' : undefined,
                                borderRadius: 6,
                                paddingInline: 8
                              }}
                              actions={[
                                <Button key="up" size="small" type="text" disabled={!editable || index === 0} onClick={(e) => { e.stopPropagation(); moveStep(index, -1) }}>
                                  ↑
                                </Button>,
                                <Button key="down" size="small" type="text" disabled={!editable || index === draftSteps.length - 1} onClick={(e) => { e.stopPropagation(); moveStep(index, 1) }}>
                                  ↓
                                </Button>,
                                <Button
                                  key="del"
                                  size="small"
                                  type="text"
                                  danger
                                  disabled={!editable}
                                  onClick={(e) => {
                                    e.stopPropagation()
                                    setDraftSteps((prev) => prev.filter((_, i) => i !== index))
                                  }}
                                >
                                  ×
                                </Button>
                              ]}
                            >
                              <Space direction="vertical" size={2} style={{ width: '100%' }}>
                                <Typography.Text>
                                  {index + 1}. {s.instruction || '（未填写步骤说明）'}
                                </Typography.Text>
                                <Space size={4} wrap>
                                  {s.required === false ? <Tag>选填</Tag> : <Tag color="blue">必填</Tag>}
                                  {s.sourceRefIds && s.sourceRefIds.length > 0 ? (
                                    <Tag color="green">已关联 {s.sourceRefIds.length} 条来源</Tag>
                                  ) : (
                                    <Tag color="orange">无来源</Tag>
                                  )}
                                  {s.officialEntryUrl && <Tag>官方入口</Tag>}
                                </Space>
                              </Space>
                            </List.Item>
                          )}
                        />
                      )}
                    </Card>
                  </Col>

                  <Col xs={24} lg={10}>
                    <Card
                      size="small"
                      title={activeStep == null ? '步骤属性' : `步骤 ${activeStep + 1} 属性`}
                      extra={
                        <Space>
                          <Button
                            size="small"
                            disabled={!version || version.reviewState === 'DRAFT'}
                            onClick={() => {
                              instantiateForm.setFieldsValue({ title: template.title })
                              setInstantiateOpen(true)
                            }}
                          >
                            实例化为事项
                          </Button>
                          <Button
                            size="small"
                            type="primary"
                            disabled={!editable}
                            onClick={() => {
                              publishForm.setFieldsValue({ allowPersonalUnverified: false })
                              setPublishOpen(true)
                            }}
                          >
                            复核发布
                          </Button>
                        </Space>
                      }
                    >
                      {activeStep == null || !draftSteps[activeStep] ? (
                        <Typography.Text type="secondary">选择左侧步骤进行编辑</Typography.Text>
                      ) : (
                        <Space direction="vertical" style={{ width: '100%' }} size="middle">
                          <div>
                            <Typography.Text strong>步骤说明</Typography.Text>
                            <Input.TextArea
                              rows={4}
                              maxLength={1000}
                              disabled={!editable}
                              value={draftSteps[activeStep].instruction}
                              onChange={(e) => updateStep(activeStep, { instruction: e.target.value })}
                            />
                          </div>
                          <Space>
                            <Typography.Text>必填</Typography.Text>
                            <Switch
                              disabled={!editable}
                              checked={draftSteps[activeStep].required !== false}
                              onChange={(checked) => updateStep(activeStep, { required: checked })}
                            />
                          </Space>
                          <div>
                            <Typography.Text strong>官方入口 URL（选填）</Typography.Text>
                            <Input
                              maxLength={2048}
                              disabled={!editable}
                              placeholder="https://"
                              value={draftSteps[activeStep].officialEntryUrl}
                              onChange={(e) => updateStep(activeStep, { officialEntryUrl: e.target.value })}
                            />
                          </div>
                          <div>
                            <Typography.Text strong>关联来源证据（必填步骤发布前必须有）</Typography.Text>
                            <Select
                              mode="multiple"
                              style={{ width: '100%', marginTop: 4 }}
                              disabled={!editable}
                              placeholder={evidenceOptions.length ? '选择带原文引文的证据' : '当前空间还没有来源证据'}
                              value={draftSteps[activeStep].sourceRefIds ?? []}
                              onChange={(v) => updateStep(activeStep, { sourceRefIds: v })}
                              options={evidenceOptions}
                              showSearch
                              optionFilterProp="label"
                            />
                            {evidenceOptions.length === 0 && (
                              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                                请先在“来源管理”导入官方页面或通知，再回来关联。
                              </Typography.Text>
                            )}
                          </div>
                          <Divider style={{ margin: '4px 0' }} />
                          <Descriptions column={1} size="small">
                            <Descriptions.Item label="版本">
                              v{version.versionNo} · {PROCESS_REVIEW_META[version.reviewState]?.label}
                            </Descriptions.Item>
                            <Descriptions.Item label="说明">
                              发布时会校验必填步骤的来源引用完整性；不满足时只能以“个人未核验”发布。
                            </Descriptions.Item>
                          </Descriptions>
                        </Space>
                      )}
                    </Card>
                  </Col>
                </Row>
              )}
            </Space>
          ) : null}
        </Col>
      </Row>

      <Modal
        title="新建流程模板"
        open={createOpen}
        onCancel={() => setCreateOpen(false)}
        onOk={() => createForm.submit()}
        okText="创建"
        confirmLoading={createMut.isPending}
        destroyOnHidden
      >
        <Form form={createForm} layout="vertical" onFinish={(v) => createMut.mutate(v)}>
          <Form.Item label="标题" name="title" rules={[{ required: true, message: '请输入标题' }, { max: 200 }]}>
            <Input maxLength={200} placeholder="例如：学生助学金申请" />
          </Form.Item>
          <Form.Item label="适用地区/学校（选填）" name="regionCode">
            <Input maxLength={32} placeholder="例如：CN-ZJ 或 某某大学" />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title="复核发布"
        open={publishOpen}
        onCancel={() => setPublishOpen(false)}
        onOk={() => publishForm.submit()}
        okText="发布"
        confirmLoading={publishMut.isPending}
        destroyOnHidden
      >
        <Form
          form={publishForm}
          layout="vertical"
          onFinish={(v) => publishMut.mutate(v)}
        >
          <Form.Item label="复核说明（选填）" name="reviewerNote">
            <Input.TextArea rows={2} maxLength={500} />
          </Form.Item>
          <Form.Item
            label="允许以“个人未核验”发布"
            name="allowPersonalUnverified"
            valuePropName="checked"
            tooltip="仅在来源不完整时使用；会显著标注非官方"
          >
            <Switch />
          </Form.Item>
          <Alert
            type="info"
            showIcon
            message="双人复核要求"
            description="进入“已核验”需要复核人不同于创建人且所有必填步骤都有来源引用；否则只能发布为“个人未核验”。"
          />
        </Form>
      </Modal>

      <Modal
        title="实例化为事项"
        open={instantiateOpen}
        onCancel={() => setInstantiateOpen(false)}
        onOk={() => instantiateForm.submit()}
        okText="创建事项"
        confirmLoading={instantiateMut.isPending}
        destroyOnHidden
      >
        <Form form={instantiateForm} layout="vertical" onFinish={(v) => instantiateMut.mutate(v)}>
          <Form.Item label="事项标题" name="title">
            <Input maxLength={128} />
          </Form.Item>
          <Form.Item label="截止时间（选填）" name="dueAt">
            <DatePicker showTime style={{ width: '100%' }} format="YYYY-MM-DD HH:mm" />
          </Form.Item>
          <Alert
            type="info"
            showIcon
            message="步骤会固化到当前发布版本；后续流程修改不会改变已创建的事项。"
          />
        </Form>
      </Modal>
    </Space>
  )
}
