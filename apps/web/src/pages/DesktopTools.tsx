import { useEffect, useRef, useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { Alert, Button, Card, Descriptions, Empty, Space, Spin, Tag, Typography, message } from 'antd'
import { getErrorMessage, sourceApi } from '../lib/api'
import { useAuth } from '../lib/auth'
import {
  desktopDiagnostics,
  isTauri,
  listenFileDrop,
  readDesktopTextFile,
  type DesktopDiagnostics,
  type DesktopTextFile
} from '../lib/desktop'

const { Text, Paragraph } = Typography
const PREVIEW_LIMIT = 4000
const SHARE_TEXT_MAX = 20000 // 后端 SourceShareRequest.text @Size(max=20000)

/** 桌面端工具：真实 WebView2 诊断 + 本地文本文件导入为“来源”（原文证据，生成待确认候选）。 */
export default function DesktopTools() {
  const queryClient = useQueryClient()
  const { currentWorkspace } = useAuth()
  const workspaceId = currentWorkspace?.id

  const [diag, setDiag] = useState<DesktopDiagnostics | null>(null)
  const [diagLoading, setDiagLoading] = useState(false)
  const [file, setFile] = useState<DesktopTextFile | null>(null)
  const [reading, setReading] = useState(false)
  const [dragOver, setDragOver] = useState(false)
  const unlistenRef = useRef<(() => void) | null>(null)
  const fileInputRef = useRef<HTMLInputElement>(null)
  // 用 ref 保存最新 workspaceId，避免拖放监听闭包捕获旧值。
  const workspaceIdRef = useRef<string | undefined>(workspaceId)
  workspaceIdRef.current = workspaceId

  const inTauri = isTauri()

  useEffect(() => {
    if (!inTauri) return
    let cancelled = false
    setDiagLoading(true)
    desktopDiagnostics()
      .then((d) => {
        if (!cancelled) setDiag(d)
      })
      .catch((err) => {
        if (!cancelled) message.error(getErrorMessage(err, '读取桌面诊断失败'))
      })
      .finally(() => {
        if (!cancelled) setDiagLoading(false)
      })

    let unlisten: (() => void) | null = null
    listenFileDrop((paths) => {
      const ws = workspaceIdRef.current
      if (!ws) {
        message.warning('请先选择协同空间')
        return
      }
      const path = paths[0]
      setReading(true)
      readDesktopTextFile(path)
        .then((f) => setFile(f))
        .catch((err) => message.error(getErrorMessage(err, '读取文件失败')))
        .finally(() => setReading(false))
    })
      .then((u) => {
        // 若组件已在 Promise resolve 前卸载，立即取消监听，避免泄漏与卸载后 setState。
        if (cancelled) {
          u()
          return
        }
        unlisten = u
        unlistenRef.current = u
      })
      .catch(() => {
        /* 拖放监听注册失败不阻断页面 */
      })

    return () => {
      cancelled = true
      unlisten?.()
      unlistenRef.current = null
    }
  }, [inTauri])

  function readBrowserFile(f: File) {
    if (!workspaceIdRef.current) {
      message.warning('请先选择协同空间')
      return
    }
    if (f.size > 512 * 1024) {
      message.error('文件超过 512KB，请先在本地截取关键段落')
      return
    }
    const reader = new FileReader()
    reader.onload = () => {
      const content = typeof reader.result === 'string' ? reader.result : ''
      setFile({ name: f.name, path: '', size: f.size, content })
    }
    reader.onerror = () => message.error('读取文件失败（仅支持 UTF-8 文本）')
    reader.readAsText(f, 'utf-8')
  }

  function handleFileInput(e: React.ChangeEvent<HTMLInputElement>) {
    const f = e.target.files?.[0]
    if (f) readBrowserFile(f)
    e.target.value = ''
  }

  const importMut = useMutation({
    mutationFn: (f: DesktopTextFile) => {
      const truncated = f.content.length > SHARE_TEXT_MAX
      const text = truncated ? f.content.slice(0, SHARE_TEXT_MAX) : f.content
      return sourceApi
        .share({
          workspaceId: workspaceId!,
          sourceType: 'FILE',
          sourceApp: 'desktop',
          // 绝对路径只在本机展示，不上传服务端（避免泄露本地路径/用户名）。
          sourceKey: f.name,
          title: f.name,
          text,
          timezone: 'Asia/Shanghai'
        })
        .then((r) => ({ r, truncated }))
    },
    onSuccess: ({ truncated }) => {
      message.success(truncated ? '已导入（文本超 20000 字已截断），请到「待确认收件箱」核对候选' : '已导入为来源，请到「待确认收件箱」核对候选')
      void queryClient.invalidateQueries({ queryKey: ['sources', workspaceId] })
      void queryClient.invalidateQueries({ queryKey: ['inbox', workspaceId] })
      setFile(null)
    },
    onError: (err) => message.error(getErrorMessage(err, '导入失败'))
  })

  const preview = file ? (file.content.length > PREVIEW_LIMIT ? file.content.slice(0, PREVIEW_LIMIT) + '…' : file.content) : ''

  return (
    <Space direction="vertical" size="large" style={{ width: '100%' }}>
      <Card title="桌面环境诊断" loading={diagLoading}>
        {!inTauri ? (
          <Alert type="info" showIcon message="当前为浏览器环境，桌面诊断与本地文件读取不可用。在 Tauri 桌面端打开本页可启用。" />
        ) : diag ? (
          <Descriptions column={1} bordered size="small">
            <Descriptions.Item label="平台">{diag.platform}</Descriptions.Item>
            <Descriptions.Item label="应用版本">{diag.appVersion}</Descriptions.Item>
            <Descriptions.Item label="WebView2 运行时">
              {diag.webview2 ? <Tag color="green">{diag.webview2}</Tag> : <Tag color="red">未检测到</Tag>}
            </Descriptions.Item>
          </Descriptions>
        ) : (
          <Empty description="暂无诊断信息" />
        )}
      </Card>

      <Card title="导入本地文件为来源">
        <Alert
          type="warning"
          showIcon
          style={{ marginBottom: 16 }}
          message="导入后仅生成“原文证据 + 待确认候选”，不会自动创建事项、截止日期或提醒，最终确认由你完成。"
        />
        <div
          onDragOver={(e) => {
            e.preventDefault()
            setDragOver(true)
          }}
          onDragLeave={() => setDragOver(false)}
          onDrop={(e) => {
            e.preventDefault()
            setDragOver(false)
            if (!inTauri) {
              // 浏览器：DOM 拖放直接用 File API 读取（不访问原生绝对路径）。
              const f = e.dataTransfer.files?.[0]
              if (f) readBrowserFile(f)
            }
            // Tauri：拖放由 listenFileDrop 接管，此处不重复处理。
          }}
          style={{
            border: `2px dashed ${dragOver ? '#1677ff' : '#d9d9d9'}`,
            borderRadius: 8,
            padding: 32,
            textAlign: 'center',
            background: dragOver ? '#e6f4ff' : '#fafafa'
          }}
        >
          {reading ? (
            <Spin />
          ) : (
            <>
              <Paragraph type="secondary" style={{ marginBottom: 8 }}>
                {inTauri ? '将单个文本文件拖入此处' : '将单个文本文件拖入此处（浏览器模式通过 File API 读取，无法获取原生路径）'}
              </Paragraph>
              <Text type="secondary">仅支持 UTF-8 文本，单文件 ≤ 512KB；上传时单条来源文本限 20000 字（超长自动截断并提示）</Text>
            </>
          )}
        </div>
        <div style={{ marginTop: 12 }}>
          <input
            ref={fileInputRef}
            type="file"
            accept=".txt,.md,.csv,.json,.log,.xml,.yml,.yaml,.ini,.conf,text/plain"
            style={{ display: 'none' }}
            onChange={handleFileInput}
          />
          <Button onClick={() => fileInputRef.current?.click()} disabled={!workspaceId}>
            选择文件…
          </Button>
        </div>

        {file && (
          <Card
            type="inner"
            title={file.name}
            extra={
              <Space>
                {file.path ? <Tag>{file.path}</Tag> : <Tag>（浏览器读取）</Tag>}
                <Tag>{(file.size / 1024).toFixed(1)} KB</Tag>
                {file.content.length > SHARE_TEXT_MAX && <Tag color="orange">将截断至 {SHARE_TEXT_MAX} 字</Tag>}
              </Space>
            }
            style={{ marginTop: 16 }}
          >
            <Paragraph
              style={{
                maxHeight: 240,
                overflow: 'auto',
                background: '#f6f8fa',
                padding: 12,
                borderRadius: 6,
                whiteSpace: 'pre-wrap',
                fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
                fontSize: 12
              }}
            >
              {preview}
            </Paragraph>
            <Space>
              <Button type="primary" loading={importMut.isPending} onClick={() => importMut.mutate(file)} disabled={!workspaceId}>
                导入为来源（生成待确认候选）
              </Button>
              <Button onClick={() => setFile(null)}>取消</Button>
            </Space>
          </Card>
        )}
      </Card>
    </Space>
  )
}
