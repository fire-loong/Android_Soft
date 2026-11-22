import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react'
import { useNavigate } from 'react-router-dom'
import { authApi, workspaceApi, type LoginPayload, type RegisterPayload } from './api'
import { AUTH_CLEARED_EVENT } from './authEvents'
import { clearTokens, isAuthenticated, setTokens } from './authTokens'
import type { User, Workspace } from './types'

interface AuthContextValue {
  user: User | null
  initializing: boolean
  workspaces: Workspace[]
  currentWorkspace: Workspace | null
  setCurrentWorkspaceId: (id: string) => void
  refreshWorkspaces: () => Promise<void>
  login: (payload: LoginPayload) => Promise<void>
  register: (payload: RegisterPayload) => Promise<void>
  logout: () => Promise<void>
}

const AuthContext = createContext<AuthContextValue | null>(null)

const CURRENT_WS_KEY = 'actionloop.currentWorkspaceId'

export function AuthProvider({ children }: { children: ReactNode }) {
  const navigate = useNavigate()
  const [user, setUser] = useState<User | null>(null)
  const [initializing, setInitializing] = useState(true)
  const [workspaces, setWorkspaces] = useState<Workspace[]>([])
  const [currentWorkspaceId, setCurrentWorkspaceIdState] = useState<string | null>(null)

  const applyWorkspaces = useCallback((list: Workspace[]) => {
    setWorkspaces(list)
    setCurrentWorkspaceIdState((prev) => {
      if (prev && list.some((w) => w.id === prev)) return prev
      const stored = localStorage.getItem(CURRENT_WS_KEY)
      if (stored && list.some((w) => w.id === stored)) return stored
      return list[0]?.id ?? null
    })
  }, [])

  const loadWorkspaces = useCallback(async () => {
    try {
      applyWorkspaces(await workspaceApi.list())
    } catch {
      setWorkspaces([])
    }
  }, [applyWorkspaces])

  const init = useCallback(async () => {
    if (!isAuthenticated()) {
      setInitializing(false)
      return
    }
    try {
      // GET /me 返回 { user, workspaces }，一次拿全，避免再发一次 list 请求。
      const me = await authApi.me()
      setUser(me.user)
      applyWorkspaces(me.workspaces)
    } catch {
      clearTokens()
      setUser(null)
    } finally {
      setInitializing(false)
    }
  }, [applyWorkspaces])

  useEffect(() => {
    void init()
  }, [init])

  // 监听 refresh 失败事件，同步清空用户态。
  useEffect(() => {
    const handler = () => {
      setUser(null)
      setWorkspaces([])
      setCurrentWorkspaceIdState(null)
      navigate('/login', { replace: true })
    }
    window.addEventListener(AUTH_CLEARED_EVENT, handler)
    return () => window.removeEventListener(AUTH_CLEARED_EVENT, handler)
  }, [navigate])

  const login = useCallback(
    async (payload: LoginPayload) => {
      const session = await authApi.login(payload)
      setTokens({ accessToken: session.accessToken, refreshToken: session.refreshToken })
      setUser(session.user)
      await loadWorkspaces()
    },
    [loadWorkspaces]
  )

  const register = useCallback(
    async (payload: RegisterPayload) => {
      const session = await authApi.register(payload)
      setTokens({ accessToken: session.accessToken, refreshToken: session.refreshToken })
      setUser(session.user)
      await loadWorkspaces()
    },
    [loadWorkspaces]
  )

  const logout = useCallback(async () => {
    try {
      await authApi.logout()
    } catch {
      // 登出请求失败不阻塞本地清理
    }
    clearTokens()
    setUser(null)
    setWorkspaces([])
    setCurrentWorkspaceIdState(null)
    localStorage.removeItem(CURRENT_WS_KEY)
    navigate('/login', { replace: true })
  }, [navigate])

  const setCurrentWorkspaceId = useCallback((id: string) => {
    localStorage.setItem(CURRENT_WS_KEY, id)
    setCurrentWorkspaceIdState(id)
  }, [])

  const currentWorkspace = useMemo(
    () => workspaces.find((w) => w.id === currentWorkspaceId) ?? null,
    [workspaces, currentWorkspaceId]
  )

  const value: AuthContextValue = {
    user,
    initializing,
    workspaces,
    currentWorkspace,
    setCurrentWorkspaceId,
    refreshWorkspaces: loadWorkspaces,
    login,
    register,
    logout
  }

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext)
  if (!ctx) throw new Error('useAuth 必须在 AuthProvider 内使用')
  return ctx
}
