import { QueryClientProvider } from '@tanstack/react-query'
import { App as AntApp, ConfigProvider, Spin } from 'antd'
import zhCN from 'antd/locale/zh_CN'
import type { ReactNode } from 'react'
import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom'
import AppLayout from './components/AppLayout'
import { AuthProvider, useAuth } from './lib/auth'
import { queryClient } from './lib/queryClient'
import Assist from './pages/Assist'
import Audit from './pages/Audit'
import DesktopTools from './pages/DesktopTools'
import Inbox from './pages/Inbox'
import Login from './pages/Login'
import Memory from './pages/Memory'
import Processes from './pages/Processes'
import Sources from './pages/Sources'
import TaskDetail from './pages/TaskDetail'
import Today from './pages/Today'
import Workspaces from './pages/Workspaces'

function RequireAuth({ children }: { children: ReactNode }) {
  const { user, initializing } = useAuth()
  if (initializing) {
    return (
      <div style={{ minHeight: '100vh', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
        <Spin size="large" />
      </div>
    )
  }
  if (!user) return <Navigate to="/login" replace />
  return children
}

function AppRoutes() {
  return (
    <Routes>
      <Route path="/login" element={<Login />} />
      <Route
        path="/"
        element={
          <RequireAuth>
            <AppLayout />
          </RequireAuth>
        }
      >
        <Route index element={<Navigate to="/today" replace />} />
        <Route path="today" element={<Today />} />
        <Route path="inbox" element={<Inbox />} />
        <Route path="tasks/:id" element={<TaskDetail />} />
        <Route path="memory" element={<Memory />} />
        <Route path="processes" element={<Processes />} />
        <Route path="workspaces" element={<Workspaces />} />
        <Route path="assist" element={<Assist />} />
        <Route path="sources" element={<Sources />} />
        <Route path="audit" element={<Audit />} />
        <Route path="desktop-tools" element={<DesktopTools />} />
      </Route>
      <Route path="*" element={<Navigate to="/today" replace />} />
    </Routes>
  )
}

export default function App() {
  return (
    <QueryClientProvider client={queryClient}>
      <ConfigProvider locale={zhCN} theme={{ token: { colorPrimary: '#1677ff' } }}>
        <AntApp>
          <BrowserRouter>
            <AuthProvider>
              <AppRoutes />
            </AuthProvider>
          </BrowserRouter>
        </AntApp>
      </ConfigProvider>
    </QueryClientProvider>
  )
}
