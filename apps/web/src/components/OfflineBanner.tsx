import { Alert } from 'antd'
import { useEffect, useState } from 'react'

/** 离线提示（指导书 9.3 六态之“离线/缓存”）：只提示，不阻断已缓存数据展示。 */
export default function OfflineBanner() {
  const [online, setOnline] = useState(() => navigator.onLine)

  useEffect(() => {
    const on = () => setOnline(true)
    const off = () => setOnline(false)
    window.addEventListener('online', on)
    window.addEventListener('offline', off)
    return () => {
      window.removeEventListener('online', on)
      window.removeEventListener('offline', off)
    }
  }, [])

  if (online) return null
  return (
    <Alert
      banner
      type="warning"
      message="当前处于离线状态：已加载的数据仍可查看，写操作会在恢复网络后需要重新提交。"
      style={{ marginBottom: 12 }}
    />
  )
}
