import { Avatar, Dropdown, Layout, Menu, Select, Space, Typography, type MenuProps } from 'antd'
import { Outlet, useLocation, useNavigate } from 'react-router-dom'
import { useAuth } from '../lib/auth'
import { isTauri } from '../lib/desktop'
import OfflineBanner from './OfflineBanner'

const { Sider, Content, Header } = Layout

const NAV = [
  { key: 'today', path: '/today', label: '工作台' },
  { key: 'inbox', path: '/inbox', label: '待确认收件箱' },
  { key: 'memory', path: '/memory', label: '个人记忆' },
  { key: 'processes', path: '/processes', label: '流程工作室' },
  { key: 'workspaces', path: '/workspaces', label: '协同空间' },
  { key: 'assist', path: '/assist', label: '远程协助' },
  { key: 'sources', path: '/sources', label: '来源管理' },
  { key: 'audit', path: '/audit', label: '审计与设置' },
  { key: 'desktop-tools', path: '/desktop-tools', label: '桌面工具', desktopOnly: true }
]

export default function AppLayout() {
  const navigate = useNavigate()
  const location = useLocation()
  const { user, workspaces, currentWorkspace, setCurrentWorkspaceId, logout } = useAuth()

  const tauri = isTauri()
  const visibleNav = NAV.filter((n) => !n.desktopOnly || tauri)
  const active = visibleNav.find((n) => location.pathname.startsWith(n.path))
  const selectedKey = active?.key ?? 'today'

  const userMenu: MenuProps['items'] = [
    { key: 'logout', label: '退出登录', onClick: () => void logout() }
  ]

  return (
    <Layout style={{ minHeight: '100vh' }}>
      <Sider breakpoint="lg" collapsible width={220}>
        <div style={{ height: 56, display: 'flex', alignItems: 'center', justifyContent: 'center', color: '#fff', fontWeight: 600 }}>
          ActionLoop
        </div>
        <Menu
          theme="dark"
          mode="inline"
          selectedKeys={[selectedKey]}
          items={visibleNav.map((n) => ({ key: n.key, label: n.label }))}
          onClick={({ key }) => {
            const target = visibleNav.find((n) => n.key === key)
            if (target) navigate(target.path)
          }}
        />
      </Sider>
      <Layout>
        <Header
          style={{
            background: '#fff',
            padding: '0 24px',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
            borderBottom: '1px solid #f0f0f0'
          }}
        >
          <Space>
            <Typography.Text strong>当前空间</Typography.Text>
            <Select
              style={{ width: 260 }}
              value={currentWorkspace?.id}
              onChange={(val) => setCurrentWorkspaceId(val)}
              options={workspaces.map((w) => ({
                value: w.id,
                label: `${w.name}（${w.type === 'PERSONAL' ? '个人' : '团队'}）`
              }))}
            />
          </Space>
          <Dropdown menu={{ items: userMenu }} placement="bottomRight">
            <Space style={{ cursor: 'pointer' }}>
              <Avatar style={{ backgroundColor: '#1677ff' }}>
                {(user?.displayName ?? user?.username ?? '?').slice(0, 1).toUpperCase()}
              </Avatar>
              <Typography.Text>{user?.displayName ?? user?.username}</Typography.Text>
            </Space>
          </Dropdown>
        </Header>
        <Content style={{ margin: 24, padding: 24, background: '#fff', borderRadius: 8, minHeight: 360 }}>
          <OfflineBanner />
          <Outlet />
        </Content>
      </Layout>
    </Layout>
  )
}
