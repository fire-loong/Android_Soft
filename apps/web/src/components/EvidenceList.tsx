import { App, Button, List, Space, Tag, Typography } from 'antd'
import type { EvidenceRef } from '../lib/types'
import { assetApi } from '../lib/api'

function rangeLabel(e: EvidenceRef): string {
  if (e.pageNo != null) return `第 ${e.pageNo} 页`
  if (e.charStart != null && e.charEnd != null) return `原文 ${e.charStart}–${e.charEnd} 字符`
  return '原文片段'
}

/** 证据引用列表：只展示原文引文与坐标，不做任何推断（指导书 4.3 / 5.3）。 */
export default function EvidenceList({
  evidence,
  workspaceId
}: {
  evidence: EvidenceRef[]
  workspaceId: string
}) {
  const { message } = App.useApp()

  const openAsset = async (assetId: string) => {
    try {
      const url = await assetApi.downloadUrl(assetId, workspaceId)
      window.open(url, '_blank', 'noopener,noreferrer')
    } catch {
      message.error('无法获取文件下载地址，请检查权限或稍后重试')
    }
  }

  if (evidence.length === 0) {
    return <Typography.Text type="secondary">该来源没有可回溯的原文片段</Typography.Text>
  }

  return (
    <List
      size="small"
      dataSource={evidence}
      renderItem={(e) => (
        <List.Item
          actions={
            e.assetId ? [
              <Button key="asset" size="small" onClick={() => void openAsset(e.assetId!)}>
                查看原文件
              </Button>
            ] : undefined
          }
        >
          <Space direction="vertical" size={2} style={{ width: '100%' }}>
            <Tag>{rangeLabel(e)}</Tag>
            <Typography.Text style={{ whiteSpace: 'pre-wrap' }}>
              {e.quote ?? '（未记录引文）'}
            </Typography.Text>
            {e.bboxJson && (
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                坐标：{e.bboxJson}
              </Typography.Text>
            )}
          </Space>
        </List.Item>
      )}
    />
  )
}
