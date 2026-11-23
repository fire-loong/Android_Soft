import { useEffect, useRef, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Button, Input, Space, Typography } from 'antd'
import { assetApi, getErrorMessage } from '../lib/api'
import type { OcrResult } from '../lib/types'

function includesKeyword(text: string, query: string): boolean {
  return query.length > 0 && text.toLocaleLowerCase().includes(query.toLocaleLowerCase())
}

function HighlightedText({ text, query }: { text: string; query: string }) {
  if (!query) return <>{text}</>
  const escaped = query.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  const parts = text.split(new RegExp(`(${escaped})`, 'giu'))
  return (
    <>
      {parts.map((part, index) =>
        index % 2 === 1 ? <mark key={index}>{part}</mark> : <span key={index}>{part}</span>
      )}
    </>
  )
}

/**
 * 长截图分段结果的原图定位（指导书 B-01）：
 * - 每段标注原图纵向范围，选择后在原图上高亮对应区域；
 * - 预览只读取已授权的私有文件签名 URL，不额外上传或改写图片；
 * - 图片高度取 OCR 元数据，缺失时按段的最大下边界兜底。
 */
export default function OcrSegmentPreview({
  assetId,
  workspaceId,
  result,
  initialQuery
}: {
  assetId: string
  workspaceId: string
  result: OcrResult
  initialQuery?: string
}) {
  const segments = result.segments ?? []
  const initialValue = initialQuery?.trim() ?? ''
  const [selected, setSelected] = useState(0)
  const [query, setQuery] = useState(initialValue)
  const [activeQuery, setActiveQuery] = useState(initialValue)
  const [loadedUrl, setLoadedUrl] = useState<string | null>(null)
  const [failedUrl, setFailedUrl] = useState<string | null>(null)
  const scrollerRef = useRef<HTMLDivElement>(null)
  const bandRef = useRef<HTMLDivElement>(null)

  const urlQuery = useQuery({
    queryKey: ['asset-url', assetId, workspaceId],
    queryFn: () => assetApi.downloadUrl(assetId, workspaceId),
    staleTime: 4 * 60 * 1000
  })
  const imageUrl = urlQuery.data
  const imageLoaded = !!imageUrl && loadedUrl === imageUrl
  const imageFailed = !!imageUrl && failedUrl === imageUrl

  const safeIndex = Math.min(selected, Math.max(0, segments.length - 1))
  const segment = segments[safeIndex]
  const totalHeight =
    result.imageHeight ?? segments.reduce((max, item) => Math.max(max, item.yOffset + item.height), 0)
  const matches = activeQuery
    ? segments.flatMap((item, index) => (includesKeyword(item.text, activeQuery) ? [index] : []))
    : []

  const executeSearch = (value: string) => {
    const normalized = value.trim()
    setQuery(value)
    setActiveQuery(normalized)
    if (normalized) {
      const firstMatch = segments.findIndex((item) => includesKeyword(item.text, normalized))
      if (firstMatch >= 0) setSelected(firstMatch)
    }
  }

  const selectRelativeMatch = (offset: number) => {
    if (matches.length === 0) return
    const current = matches.indexOf(safeIndex)
    const next =
      current < 0
        ? offset > 0
          ? 0
          : matches.length - 1
        : (current + offset + matches.length) % matches.length
    setSelected(matches[next])
  }

  useEffect(() => {
    const firstMatch = segments.findIndex((item) => includesKeyword(item.text, initialValue))
    setQuery(initialValue)
    setActiveQuery(initialValue)
    setSelected(firstMatch >= 0 ? firstMatch : 0)
  }, [assetId, result.id, initialValue])

  useEffect(() => {
    if (!imageLoaded) return
    const scroller = scrollerRef.current
    const band = bandRef.current
    if (scroller && band) {
      scroller.scrollTop = Math.max(0, band.offsetTop - scroller.clientHeight / 3)
    }
  }, [safeIndex, imageUrl, imageLoaded])

  if (!segment) {
    return null
  }

  return (
    <Space direction="vertical" size={8} style={{ width: '100%' }}>
      <Input.Search
        aria-label="搜索分段原文"
        allowClear
        size="small"
        value={query}
        onChange={(event) => {
          setQuery(event.target.value)
          if (!event.target.value) setActiveQuery('')
        }}
        onSearch={executeSearch}
        placeholder="输入原文词语，定位到所在段"
        enterButton="定位"
        style={{ maxWidth: 480 }}
      />
      <Typography.Text type="secondary" style={{ fontSize: 12 }}>
        关键词可定位到词语所在段；当前 OCR 无词级版面坐标，原图仅高亮该段范围。
      </Typography.Text>
      <Space wrap size={4}>
        <Typography.Text
          type={activeQuery && matches.length === 0 ? 'warning' : 'secondary'}
          role="status"
          aria-live="polite"
        >
          {activeQuery ? (matches.length > 0 ? `命中 ${matches.length} 段` : `未找到“${activeQuery}”`) : ''}
        </Typography.Text>
        {activeQuery && matches.length > 0 && (
          <>
            <Button size="small" onClick={() => selectRelativeMatch(-1)}>
              上一命中段
            </Button>
            <Button size="small" onClick={() => selectRelativeMatch(1)}>
              下一命中段
            </Button>
          </>
        )}
      </Space>
      <Space wrap size={4}>
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          分段定位（共 {segments.length} 段，原图 {result.imageWidth ?? '?'}×{totalHeight || '?'}）
        </Typography.Text>
        {segments.map((item, index) => (
          <Button
            key={item.id}
            size="small"
            type={index === safeIndex ? 'primary' : 'default'}
            onClick={() => setSelected(index)}
          >
            第 {index + 1} 段
          </Button>
        ))}
      </Space>
      <Typography.Text type="secondary" style={{ fontSize: 12 }}>
        第 {safeIndex + 1} 段对应原图 y={segment.yOffset}–{segment.yOffset + segment.height}
      </Typography.Text>
      {urlQuery.error ? (
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          无法读取原图预览：{getErrorMessage(urlQuery.error)}。分段文本仍可用于核对。
        </Typography.Text>
      ) : imageUrl && totalHeight > 0 ? (
        <div
          ref={scrollerRef}
          style={{
            maxHeight: 320,
            overflow: 'auto',
            border: '1px solid rgba(0, 0, 0, 0.08)',
            borderRadius: 6,
            background: '#fafafa'
          }}
        >
          <div style={{ position: 'relative' }}>
            <img
              src={imageUrl}
              alt="长截图原图"
              style={{ width: '100%', display: 'block' }}
              onError={() => {
                setFailedUrl(imageUrl)
                setLoadedUrl((current) => (current === imageUrl ? null : current))
              }}
              onLoad={() => {
                setLoadedUrl(imageUrl)
                setFailedUrl((current) => (current === imageUrl ? null : current))
              }}
            />
            <div
              ref={bandRef}
              aria-hidden
              style={{
                position: 'absolute',
                left: 0,
                right: 0,
                top: `${(segment.yOffset / totalHeight) * 100}%`,
                height: `${(segment.height / totalHeight) * 100}%`,
                border: '2px solid #1677ff',
                background: 'rgba(22, 119, 255, 0.12)',
                pointerEvents: 'none'
              }}
            />
          </div>
        </div>
      ) : null}
      {imageFailed && (
        <Typography.Text type="secondary" role="alert" style={{ fontSize: 12 }}>
          原图加载失败（签名链接可能已过期），刷新页面可重新获取；不影响已识别的分段文本。
        </Typography.Text>
      )}
      <Typography.Paragraph
        style={{ whiteSpace: 'pre-wrap', marginBottom: 0, maxHeight: 160, overflow: 'auto', fontSize: 13 }}
      >
        <HighlightedText text={segment.text} query={activeQuery} />
      </Typography.Paragraph>
    </Space>
  )
}
