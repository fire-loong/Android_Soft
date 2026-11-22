import { Alert, Button, Empty, Skeleton, Space, Typography } from 'antd'
import type { ReactNode } from 'react'
import { getErrorMessage } from '../lib/api'

interface QueryStateProps {
  loading?: boolean
  error?: unknown
  empty?: boolean
  emptyText?: ReactNode
  emptyAction?: ReactNode
  onRetry?: () => void
  skeletonRows?: number
  children: ReactNode
}

/**
 * 统一六态容器（指导书 9.3）：加载骨架、首次空状态、正常内容、可恢复错误。
 * 离线与无权限由 ErrorState 的文案区分（403 提示无权限，网络错误提示离线可重试）。
 */
export default function QueryState({
  loading,
  error,
  empty,
  emptyText = '暂无数据',
  emptyAction,
  onRetry,
  skeletonRows = 4,
  children
}: QueryStateProps) {
  if (loading) {
    return <Skeleton active paragraph={{ rows: skeletonRows }} />
  }
  if (error) {
    const err = error as { response?: { status?: number } }
    const status = err.response?.status
    const description =
      status === 403
        ? '当前空间角色无权限查看该内容。请联系空间管理员调整角色后重试。'
        : `${getErrorMessage(error)}。数据未丢失，可重试或检查网络。`
    return (
      <Alert
        type={status === 403 ? 'warning' : 'error'}
        showIcon
        message={status === 403 ? '无权限' : '加载失败'}
        description={description}
        action={
          onRetry && status !== 403 ? (
            <Button size="small" onClick={onRetry}>
              重试
            </Button>
          ) : undefined
        }
      />
    )
  }
  if (empty) {
    return (
      <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={<Typography.Text type="secondary">{emptyText}</Typography.Text>}>
        {emptyAction ? <Space>{emptyAction}</Space> : null}
      </Empty>
    )
  }
  return <>{children}</>
}
