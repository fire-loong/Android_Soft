import { useEffect, useMemo, useRef, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  Alert,
  Button,
  Card,
  Col,
  Descriptions,
  Empty,
  Form,
  Input,
  InputNumber,
  List,
  Modal,
  Popconfirm,
  Row,
  Select,
  Space,
  Switch,
  Tag,
  Typography,
  message
} from 'antd'
import QueryState from '../components/QueryState'
import { assistApi, getErrorMessage } from '../lib/api'
import { useAuth } from '../lib/auth'
import { ASSIST_STATE_META, formatDateTime, relativeTime } from '../lib/format'
import type { AssistSession, AssistTurnCredentials } from '../lib/types'

const SCOPE_OPTIONS = [
  { value: 'VIEW_SCREEN', label: '查看当前屏幕（单次授权）' },
  { value: 'HIGHLIGHT', label: '在对方屏幕上高亮（非点击）' },
  { value: 'TEXT_HINT', label: '文字提示' },
  { value: 'REMOTE_POINTER', label: '远程指针标注' }
]

const EVENT_OPTIONS = [
  { value: 'TEXT_HINT', label: '文字提示' },
  { value: 'HIGHLIGHT', label: '高亮区域' },
  { value: 'POINTER', label: '指针标注' },
  { value: 'HIGHLIGHT_CLEAR', label: '清除高亮' },
  { value: 'PAUSE', label: '暂停（请求方）' },
  { value: 'RESUME', label: '恢复（请求方）' }
]

const EVENT_TEMPLATE: Record<string, string> = {
  TEXT_HINT: '{"text":"请点击屏幕上被高亮的按钮"}',
  HIGHLIGHT: '{"bounds":{"x":0.5,"y":0.4,"width":0.3,"height":0.12},"label":"下一步点这里"}',
  POINTER: '{"bounds":{"x":0.5,"y":0.5,"width":0.02,"height":0.02},"label":"看这里"}',
  HIGHLIGHT_CLEAR: '{}',
  PAUSE: '{}',
  RESUME: '{}'
}

/** 必须绑定当前帧的只读标注类型（指导书 8.7）。 */
const FRAME_BOUND_EVENTS = new Set(['HIGHLIGHT', 'POINTER', 'TEXT_HINT'])

function parsePayloadObject(raw: string): Record<string, unknown> | null {
  try {
    const parsed = JSON.parse(raw) as unknown
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) return null
    return parsed as Record<string, unknown>
  } catch {
    return null
  }
}

export default function Assist() {
  const queryClient = useQueryClient()
  const { currentWorkspace, user } = useAuth()
  const workspaceId = currentWorkspace?.id
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [createOpen, setCreateOpen] = useState(false)
  const [createForm] = Form.useForm<{
    mode: string
    scopes: string[]
    expiresMinutes: number
    helperId?: string
    taskStepId?: string
  }>()
  const [signalForm] = Form.useForm<{ eventType: string; payload: string }>()
  const [turn, setTurn] = useState<AssistTurnCredentials | null>(null)

  const sessionsQuery = useQuery({
    queryKey: ['assist-sessions'],
    queryFn: () => assistApi.mine(),
    refetchInterval: 10_000
  })

  const session: AssistSession | null = (sessionsQuery.data ?? []).find((s) => s.id === selectedId) ?? null

  const eventsQuery = useQuery({
    queryKey: ['assist-events', selectedId],
    queryFn: () => assistApi.events(selectedId!, 0),
    enabled: !!selectedId,
    refetchInterval: 2_000
  })

  // Android 请求方通过 META 上报当前帧标识（不含画面）；标注必须绑定 10 秒内的新鲜帧。
  const latestMeta = useMemo(() => {
    const metas = (eventsQuery.data ?? []).filter((e) => e.eventType === 'META')
    if (metas.length === 0) return null
    const last = metas[metas.length - 1]
    const created = last.createdAt ? Date.parse(last.createdAt) : NaN
    if (Number.isFinite(created) && Date.now() - created > 10_000) return null
    return last
  }, [eventsQuery.data])
  const latestFrameId = useMemo(() => {
    if (!latestMeta?.payloadJson) return null
    try {
      const parsed = JSON.parse(latestMeta.payloadJson) as unknown
      if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) return null
      const frameId = (parsed as { frameId?: unknown }).frameId
      return typeof frameId === 'string' && frameId.length > 0 ? frameId : null
    } catch {
      return null
    }
  }, [latestMeta])

  // ---- WebRTC 只读画面（Android 请求方为 offerer，Web 协助者只作答）----
  const [mediaState, setMediaState] = useState<'off' | 'connecting' | 'live' | 'error'>('off')
  const [mediaNote, setMediaNote] = useState<string | null>(null)
  const videoRef = useRef<HTMLVideoElement | null>(null)
  const pcRef = useRef<RTCPeerConnection | null>(null)
  const mediaSeqRef = useRef(0)
  const lastOfferSdpRef = useRef<string | null>(null)
  const pendingIceRef = useRef<RTCIceCandidateInit[]>([])
  const mediaActive = session?.state === 'ACTIVE'

  useEffect(() => {
    if (!session || !mediaActive) return
    const sessionId = session.id
    let cancelled = false
    mediaSeqRef.current = 0
    lastOfferSdpRef.current = null
    pendingIceRef.current = []
    const pc = new RTCPeerConnection({ iceServers: [] })
    pcRef.current = pc
    setMediaState('connecting')
    setMediaNote(null)

    pc.ontrack = (event) => {
      const video = videoRef.current
      const stream = event.streams[0]
      if (video && stream) {
        video.srcObject = stream
        void video.play().catch(() => undefined)
        setMediaState('live')
      }
    }
    pc.onconnectionstatechange = () => {
      if (pc.connectionState === 'connected') setMediaState('live')
      else if (pc.connectionState === 'failed') setMediaState('error')
      else if (pc.connectionState === 'closed') setMediaState('off')
    }
    pc.onicecandidate = (event) => {
      if (!event.candidate) return
      void assistApi
        .signal(sessionId, 'ICE_CANDIDATE', {
          candidate: event.candidate.candidate,
          sdpMid: event.candidate.sdpMid,
          sdpMLineIndex: event.candidate.sdpMLineIndex
        })
        .catch(() => undefined)
    }

    // TURN 未配置时明确告知（不是静默降级）：同局域网可直连，跨网络则无法建立媒体。
    void (async () => {
      try {
        const turn = await assistApi.turnCredentials(sessionId)
        if (cancelled) return
        if (turn.urls.length > 0) {
          pc.setConfiguration({
            iceServers: [
              {
                urls: turn.urls,
                username: turn.username ?? undefined,
                credential: turn.credential ?? undefined
              }
            ]
          })
        } else {
          setMediaNote('服务端未配置 TURN URL，仅同一局域网内可能建立媒体通道。')
        }
      } catch {
        if (!cancelled) {
          setMediaNote(
            '服务端未配置 TURN 凭证（TURN_UNAVAILABLE）：仅同一局域网内可能建立媒体通道，跨网络无法观看画面。标注与文字提示不受影响。'
          )
        }
      }
    })()

    return () => {
      cancelled = true
      pc.close()
      if (pcRef.current === pc) pcRef.current = null
      if (videoRef.current) videoRef.current.srcObject = null
      setMediaState('off')
    }
  }, [session?.id, mediaActive])

  // 轮询到的 OFFER/ICE_CANDIDATE：按 seq 去重后作答；媒体信令不进入标注状态机。
  useEffect(() => {
    const pc = pcRef.current
    const current = session
    const events = eventsQuery.data ?? []
    if (!pc || !current) return
    let cancelled = false
    void (async () => {
      for (const event of events) {
        if (cancelled) return
        if (event.seq <= mediaSeqRef.current) continue
        mediaSeqRef.current = event.seq
        if (event.eventType === 'OFFER') {
          const payload = parsePayloadObject(event.payloadJson)
          const sdp = typeof payload?.sdp === 'string' ? payload.sdp : null
          if (!sdp || sdp === lastOfferSdpRef.current) continue
          if (pc.signalingState !== 'stable') continue
          try {
            await pc.setRemoteDescription({ type: 'offer', sdp })
            lastOfferSdpRef.current = sdp
            const answer = await pc.createAnswer()
            await pc.setLocalDescription(answer)
            await assistApi.signal(current.id, 'ANSWER', { type: 'answer', sdp: answer.sdp })
            const queued = pendingIceRef.current.splice(0)
            for (const candidate of queued) {
              await pc.addIceCandidate(candidate).catch(() => undefined)
            }
          } catch {
            if (!cancelled) setMediaState('error')
          }
        } else if (event.eventType === 'ICE_CANDIDATE') {
          const payload = parsePayloadObject(event.payloadJson)
          const candidate = typeof payload?.candidate === 'string' ? payload.candidate : null
          if (!candidate) continue
          const init: RTCIceCandidateInit = {
            candidate,
            sdpMid: typeof payload?.sdpMid === 'string' ? payload.sdpMid : null,
            sdpMLineIndex: typeof payload?.sdpMLineIndex === 'number' ? payload.sdpMLineIndex : 0
          }
          if (pc.remoteDescription) {
            await pc.addIceCandidate(init).catch(() => undefined)
          } else {
            pendingIceRef.current.push(init)
          }
        }
      }
    })()
    return () => {
      cancelled = true
    }
  }, [eventsQuery.data, session?.id])

  const createMut = useMutation({
    mutationFn: (v: { mode: string; scopes: string[]; expiresMinutes: number; helperId?: string; taskStepId?: string }) =>
      assistApi.create({ workspaceId: workspaceId!, ...v }),
    onSuccess: (s) => {
      message.success(s.state === 'ACTIVE' ? '本机引导已激活，可立即发送高亮/文字提示' : '协助会话已创建，等待对方接受')
      setCreateOpen(false)
      createForm.resetFields()
      setSelectedId(s.id)
      void queryClient.invalidateQueries({ queryKey: ['assist-sessions'] })
    },
    onError: (err) => message.error(getErrorMessage(err, '创建失败'))
  })

  const acceptMut = useMutation({
    mutationFn: (id: string) => assistApi.accept(id),
    onSuccess: () => {
      message.success('已接受协助请求')
      void queryClient.invalidateQueries({ queryKey: ['assist-sessions'] })
    },
    onError: (err) => message.error(getErrorMessage(err, '接受失败'))
  })

  const stopMut = useMutation({
    mutationFn: (id: string) => assistApi.stop(id, '参与者主动停止'),
    onSuccess: () => {
      message.success('会话已停止，画面与高亮应立即释放')
      setTurn(null)
      void queryClient.invalidateQueries({ queryKey: ['assist-sessions'] })
    },
    onError: (err) => message.error(getErrorMessage(err, '停止失败'))
  })

  const signalMut = useMutation({
    mutationFn: (v: { eventType: string; payload: string }) => {
      const raw = JSON.parse(v.payload || '{}') as unknown
      if (!raw || typeof raw !== 'object' || Array.isArray(raw)) {
        throw new Error('负载必须是 JSON 对象')
      }
      const parsed = raw as Record<string, unknown>
      if (FRAME_BOUND_EVENTS.has(v.eventType)) {
        if (!latestFrameId) {
          throw new Error('尚未收到对方当前帧（frameId），不能标注；请确认对方已发起求助并开启只读无障碍')
        }
        parsed.frameId = latestFrameId
      }
      if (v.eventType === 'HIGHLIGHT' || v.eventType === 'POINTER') {
        // 兼容旧模板的 x/y/w/h，统一转成 Android 的 bounds{width,height}。
        if (!parsed.bounds && typeof parsed.x === 'number') {
          parsed.bounds = {
            x: parsed.x,
            y: parsed.y,
            width: parsed.w ?? 0.02,
            height: parsed.h ?? 0.02
          }
          delete parsed.x
          delete parsed.y
          delete parsed.w
          delete parsed.h
        }
        if (typeof parsed.expiresAt !== 'number' || parsed.expiresAt <= Date.now()) {
          parsed.expiresAt = Date.now() + 60_000
        }
      }
      return assistApi.signal(selectedId!, v.eventType, parsed)
    },
    onSuccess: () => {
      message.success('信令已发送（只读提示，不会替对方点击）')
      void queryClient.invalidateQueries({ queryKey: ['assist-events', selectedId] })
    },
    onError: (err) => {
      const msg = getErrorMessage(err, '信令被拒绝')
      message.error(msg)
    }
  })

  const turnMut = useMutation({
    mutationFn: (id: string) => assistApi.turnCredentials(id),
    onSuccess: (c) => {
      setTurn(c)
      message.success(`TURN 凭证已签发，${c.ttlSeconds} 秒后失效`)
    },
    onError: (err) => message.error(getErrorMessage(err, '无法获取 TURN 凭证'))
  })

  return (
    <Space direction="vertical" style={{ width: '100%' }} size="middle">
      <Space style={{ width: '100%', justifyContent: 'space-between', flexWrap: 'wrap' }}>
        <Space direction="vertical" size={0}>
          <Typography.Title level={4} style={{ margin: 0 }}>
            远程协助控制台
          </Typography.Title>
          <Typography.Text type="secondary">
            单次授权、默认只读；协助者可看画面、画标注、发文字，但不存在点击/滑动/输入/支付通道。
          </Typography.Text>
        </Space>
        <Button type="primary" onClick={() => setCreateOpen(true)}>
          发起协助
        </Button>
      </Space>

      <Alert
        type="info"
        showIcon
        message="安全边界"
        description="服务端白名单只接受 OFFER/ANSWER/ICE_CANDIDATE/HIGHLIGHT/POINTER/TEXT_HINT 等只读信令；伪造 CLICK/SWIPE/INPUT/COMMIT_PAYMENT 会被拒绝并写入安全审计。原始屏幕帧默认零落盘，WebRTC 使用 DTLS-SRTP，TURN 凭证短期有效。"
      />

      <Row gutter={16}>
        <Col xs={24} lg={9}>
          <Card size="small" title="我的协助会话">
            <QueryState
              loading={sessionsQuery.isLoading}
              error={sessionsQuery.error}
              empty={(sessionsQuery.data ?? []).length === 0}
              emptyText="还没有协助会话"
              onRetry={() => void sessionsQuery.refetch()}
            >
              <List
                size="small"
                dataSource={sessionsQuery.data ?? []}
                renderItem={(s) => (
                  <List.Item
                    onClick={() => setSelectedId(s.id)}
                    style={{
                      cursor: 'pointer',
                      background: selectedId === s.id ? '#e6f4ff' : undefined,
                      borderRadius: 6,
                      paddingInline: 8
                    }}
                    actions={[
                      (s.state === 'CONSENT_PENDING' || s.state === 'IDLE') &&
                      s.requesterId !== user?.id &&
                      (!s.helperId || s.helperId === user?.id) ? (
                        <Button
                          key="accept"
                          size="small"
                          type="primary"
                          loading={acceptMut.isPending}
                          onClick={(e) => {
                            e.stopPropagation()
                            acceptMut.mutate(s.id)
                          }}
                        >
                          接受
                        </Button>
                      ) : s.state === 'IDLE' || s.state === 'CONSENT_PENDING' ? (
                        <Typography.Text key="wait" type="secondary" style={{ fontSize: 12 }}>
                          等待对方接受
                        </Typography.Text>
                      ) : (
                        <Button key="open" size="small" onClick={() => setSelectedId(s.id)}>
                          打开
                        </Button>
                      )
                    ]}
                  >
                    <List.Item.Meta
                      title={
                        <Space wrap size={4}>
                          <Tag color={ASSIST_STATE_META[s.state]?.color}>{ASSIST_STATE_META[s.state]?.label}</Tag>
                          <Typography.Text style={{ fontSize: 13 }}>
                            {s.mode === 'LOCAL_GUIDANCE' ? '本机引导' : '远程标注'}
                          </Typography.Text>
                        </Space>
                      }
                      description={
                        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                          {relativeTime(s.createdAt)} · 到期 {formatDateTime(s.expiresAt)}
                          {s.helperId === user?.id ? ' · 你是协助者' : ''}
                        </Typography.Text>
                      }
                    />
                  </List.Item>
                )}
              />
            </QueryState>
          </Card>
        </Col>

        <Col xs={24} lg={15}>
          <Card
            size="small"
            title="会话控制台"
            extra={
              session ? (
                <Space>
                  <Button size="small" loading={turnMut.isPending} onClick={() => turnMut.mutate(session.id)}>
                    获取 TURN 凭证
                  </Button>
                  <Popconfirm title="立即停止协助？" description="停止后高亮与媒体应立即释放" onConfirm={() => stopMut.mutate(session.id)}>
                    <Button size="small" danger loading={stopMut.isPending}>
                      立即停止
                    </Button>
                  </Popconfirm>
                </Space>
              ) : undefined
            }
          >
            {!session ? (
              <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="选择左侧会话，或发起新的协助" />
            ) : (
              <Space direction="vertical" style={{ width: '100%' }} size="middle">
                <Descriptions column={2} size="small" bordered>
                  <Descriptions.Item label="状态">
                    <Tag color={ASSIST_STATE_META[session.state]?.color}>{ASSIST_STATE_META[session.state]?.label}</Tag>
                  </Descriptions.Item>
                  <Descriptions.Item label="模式">{session.mode === 'LOCAL_GUIDANCE' ? '本机引导' : '远程协助'}</Descriptions.Item>
                  <Descriptions.Item label="请求方">{session.requesterId.slice(0, 8)}…</Descriptions.Item>
                  <Descriptions.Item label="协助者">{session.helperId ? `${session.helperId.slice(0, 8)}…` : '待接受'}</Descriptions.Item>
                  <Descriptions.Item label="授权范围" span={2}>
                    {session.scopesJson ?? '—'}
                  </Descriptions.Item>
                  <Descriptions.Item label="到期时间" span={2}>
                    {formatDateTime(session.expiresAt)}
                  </Descriptions.Item>
                </Descriptions>

                <Alert
                  type={latestFrameId ? 'success' : 'warning'}
                  showIcon
                  message={
                    latestFrameId
                      ? `请求方当前帧：${latestFrameId.slice(0, 8)}…`
                      : '尚未收到请求方当前帧标识'
                  }
                  description="Android 端只上报 frameId / pageHash，不上传画面或控件文本。高亮、指针、文字提示都必须绑定该帧；换帧后旧标注会被本机丢弃。"
                />

                <Card
                  size="small"
                  type="inner"
                  title={
                    <Space>
                      <span>实时画面（只读）</span>
                      <Tag color={mediaState === 'live' ? 'green' : mediaState === 'error' ? 'red' : 'default'}>
                        {mediaState === 'live'
                          ? '接收中'
                          : mediaState === 'connecting'
                            ? '等待对方共享'
                            : mediaState === 'error'
                              ? '连接失败'
                              : '未连接'}
                      </Tag>
                    </Space>
                  }
                >
                  <div
                    style={{
                      position: 'relative',
                      background: '#000',
                      borderRadius: 6,
                      aspectRatio: '16 / 9',
                      overflow: 'hidden',
                      display: 'flex',
                      alignItems: 'center',
                      justifyContent: 'center'
                    }}
                  >
                    <video
                      ref={videoRef}
                      autoPlay
                      playsInline
                      muted
                      style={{ width: '100%', height: '100%', objectFit: 'contain' }}
                    />
                    {mediaState !== 'live' && (
                      <Typography.Text style={{ position: 'absolute', color: '#bbb', fontSize: 13, padding: 16, textAlign: 'center' }}>
                        对方在 Android 端点击“共享屏幕给协助者”并完成一次屏幕授权后，这里会出现实时画面。
                        画面通过 WebRTC（DTLS-SRTP）只读传输，不录音、不录像、不落盘。
                      </Typography.Text>
                    )}
                  </div>
                  {mediaNote && <Alert style={{ marginTop: 10 }} type="warning" showIcon message="TURN 未配置" description={mediaNote} />}
                  <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                    敏感页、锁屏、暂停或停止会让 Android 端立即中断画面；此页面只有观看与标注能力，没有点击/输入通道。
                  </Typography.Text>
                </Card>

                <Alert
                  type="info"
                  showIcon
                  message="信令与标注"
                  description="高亮、指针、文字提示必须绑定对方当前帧（frameId），换帧后旧标注会被本机丢弃。"
                />

                {turn && (
                  <Card size="small" type="inner" title={`TURN 凭证 · ${turn.ttlSeconds}s`}>
                    <Descriptions column={1} size="small">
                      <Descriptions.Item label="URLs">{turn.urls.join(', ') || '未配置 TURN'}</Descriptions.Item>
                      <Descriptions.Item label="用户名">{turn.username || '—'}</Descriptions.Item>
                      <Descriptions.Item label="凭证">{turn.credential ? '已签发（不在此显示完整值）' : '—'}</Descriptions.Item>
                      <Descriptions.Item label="过期">{formatDateTime(turn.expiresAt)}</Descriptions.Item>
                    </Descriptions>
                  </Card>
                )}

                <Card
                  size="small"
                  type="inner"
                  title="发送只读标注 / 文字提示"
                  extra={<Tag>无控制能力</Tag>}
                >
                  {session.state !== 'ACTIVE' && session.state !== 'PAUSED' && (
                    <Alert
                      style={{ marginBottom: 12 }}
                      type="info"
                      showIcon
                      message="会话未激活"
                      description="远程协助需协助者接受后才能发送标注；本机引导创建后即可使用。"
                    />
                  )}
                  <Form
                    form={signalForm}
                    layout="vertical"
                    initialValues={{ eventType: 'TEXT_HINT', payload: EVENT_TEMPLATE.TEXT_HINT }}
                    onFinish={(v) => signalMut.mutate(v)}
                  >
                    <Row gutter={12}>
                      <Col span={8}>
                        <Form.Item label="信令类型" name="eventType">
                          <Select
                            options={EVENT_OPTIONS}
                            onChange={(t) => signalForm.setFieldsValue({ eventType: t, payload: EVENT_TEMPLATE[t] ?? '{}' })}
                          />
                        </Form.Item>
                      </Col>
                      <Col span={16}>
                        <Form.Item
                          label="负载 JSON（坐标使用 0–1 归一化）"
                          name="payload"
                          rules={[
                            { required: true },
                            {
                              validator: (_, value: string) => {
                                try {
                                  JSON.parse(value)
                                  return Promise.resolve()
                                } catch {
                                  return Promise.reject(new Error('必须是合法 JSON'))
                                }
                              }
                            }
                          ]}
                        >
                          <Input.TextArea rows={2} />
                        </Form.Item>
                      </Col>
                    </Row>
                    <Button
                      type="primary"
                      htmlType="submit"
                      loading={signalMut.isPending}
                      disabled={session.state !== 'ACTIVE' && session.state !== 'PAUSED'}
                    >
                      发送
                    </Button>
                  </Form>
                </Card>

                <Card size="small" type="inner" title="事件流">
                  <QueryState
                    loading={eventsQuery.isLoading}
                    error={eventsQuery.error}
                    empty={(eventsQuery.data ?? []).length === 0}
                    emptyText="暂无协助事件"
                    onRetry={() => void eventsQuery.refetch()}
                  >
                    <List
                      size="small"
                      dataSource={eventsQuery.data ?? []}
                      renderItem={(e) => (
                        <List.Item>
                          <Space direction="vertical" size={2} style={{ width: '100%' }}>
                            <Space>
                              <Tag>#{e.seq}</Tag>
                              <Tag color="blue">{e.eventType}</Tag>
                              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                                {formatDateTime(e.createdAt)}
                              </Typography.Text>
                            </Space>
                            <Typography.Text code style={{ whiteSpace: 'pre-wrap', fontSize: 12 }}>
                              {e.payloadJson}
                            </Typography.Text>
                          </Space>
                        </List.Item>
                      )}
                    />
                  </QueryState>
                </Card>
              </Space>
            )}
          </Card>
        </Col>
      </Row>

      <Modal
        title="发起协助会话"
        open={createOpen}
        onCancel={() => setCreateOpen(false)}
        onOk={() => createForm.submit()}
        okText="创建邀请"
        confirmLoading={createMut.isPending}
        destroyOnHidden
      >
        <Alert
          type="info"
          showIcon
          style={{ marginBottom: 12 }}
          message="单次授权"
          description="会话到期自动失效；对方随时可以停止。协助者只获得你勾选的范围，不获得控制能力。"
        />
        <Form
          form={createForm}
          layout="vertical"
          initialValues={{ mode: 'REMOTE_ASSIST', scopes: ['VIEW_SCREEN', 'HIGHLIGHT', 'TEXT_HINT'], expiresMinutes: 15 }}
          onFinish={(v) => createMut.mutate({ ...v, helperId: v.helperId || undefined })}
        >
          <Form.Item label="模式" name="mode">
            <Select
              options={[
                { value: 'REMOTE_ASSIST', label: '远程协助（查看 + 标注）' },
                { value: 'LOCAL_GUIDANCE', label: '本机引导（不远程发送画面）' }
              ]}
            />
          </Form.Item>
          <Form.Item label="授权范围" name="scopes" rules={[{ required: true, message: '至少选择一项' }]}>
            <Select mode="multiple" options={SCOPE_OPTIONS} />
          </Form.Item>
          <Form.Item label="有效期（分钟）" name="expiresMinutes">
            <InputNumber min={5} max={60} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item label="协助者用户 ID（选填，留空则由对方接受）" name="helperId">
            <Input placeholder="UUID" />
          </Form.Item>
          <Form.Item label="关联步骤 ID（选填）" name="taskStepId">
            <Input placeholder="UUID" />
          </Form.Item>
          <Form.Item label="创建后等待对方接受" name="_note" valuePropName="checked" hidden>
            <Switch />
          </Form.Item>
        </Form>
      </Modal>
    </Space>
  )
}
