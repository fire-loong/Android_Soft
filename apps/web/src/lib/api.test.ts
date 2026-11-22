import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { api, authApi, getErrorCode, getErrorMessage } from './api'
import { clearTokens, getAccessToken, setTokens } from './authTokens'
import { AUTH_CLEARED_EVENT } from './authEvents'

describe('api 错误提取', () => {
  it('从统一信封中提取错误码', () => {
    const err = {
      response: { data: { error: { code: 'ACCESS_DENIED', message: '无权访问', requestId: 'req_1' } } }
    } as never
    expect(getErrorCode(err)).toBe('ACCESS_DENIED')
    expect(getErrorMessage(err, 'fallback')).toBe('无权访问')
  })

  it('无响应体时返回 undefined', () => {
    expect(getErrorCode({} as never)).toBeUndefined()
  })

  it('超时给出确定性提示', () => {
    expect(getErrorMessage({ code: 'ECONNABORTED' } as never)).toContain('超时')
  })

  it('无响应时提示连接后端', () => {
    expect(getErrorMessage({} as never)).toContain('无法连接后端')
  })
})

// ---- 401 自动 refresh 拦截器 ----
// 通过替换 axios 实例的 adapter 来模拟后端响应，不发起真实网络请求。

const userJson = {
  id: 'u1',
  username: 'alice',
  email: 'a@x.com',
  role: 'USER',
  status: 'ACTIVE',
  createdAt: '2025-01-01T00:00:00Z'
}
const sessionJson = { accessToken: 'new-access', refreshToken: 'new-refresh', expiresIn: 3600, user: userJson }
const meJson = { user: userJson, workspaces: [] }

function ok(data: unknown) {
  return { status: 200, statusText: 'OK', headers: {}, config: {} as never, data }
}
function httpError(status: number, code: string, message: string, config?: unknown) {
  // 自定义 adapter 抛出的错误需自带 config/response，axios 内置 adapter 才会用 enhanceError 附加。
  const err = new Error(`HTTP ${status}`) as Error & { response: unknown; config?: unknown }
  err.response = { status, data: { error: { code, message } }, headers: {} }
  if (config) err.config = config
  return err
}

describe('auth 拦截器：401 → refresh → 重试', () => {
  let calls: { url: string }[] = []

  beforeEach(() => {
    calls = []
    clearTokens()
  })

  afterEach(() => {
    ;(api.defaults as { adapter?: unknown }).adapter = undefined
    clearTokens()
  })

  function install(adapter: (cfg: { url: string }) => Promise<unknown>) {
    ;(api.defaults as { adapter?: (c: never) => Promise<unknown> }).adapter = (cfg: { url: string }) => {
      calls.push({ url: cfg.url })
      return adapter(cfg)
    }
  }

  it('首个 401 触发 refresh 并成功重试原请求', async () => {
    setTokens({ accessToken: 'old-access', refreshToken: 'rt' })
    let meAttempts = 0
    install(async (cfg) => {
      if (cfg.url === '/auth/refresh') return ok({ code: 0, message: 'ok', data: sessionJson })
      if (cfg.url === '/me') {
        meAttempts += 1
        if (meAttempts === 1) throw httpError(401, 'AUTH_INVALID', 'access token 过期', cfg)
        return ok({ code: 0, message: 'ok', data: meJson })
      }
      throw httpError(404, 'RESOURCE_NOT_FOUND', 'not found', cfg)
    })

    const me = await authApi.me()
    expect(me.user.username).toBe('alice')
    expect(meAttempts).toBe(2)
    expect(calls.map((c) => c.url)).toEqual(['/me', '/auth/refresh', '/me'])
    expect(getAccessToken()).toBe('new-access')
  })

  it('refresh 失败时清空 token 并广播 auth-cleared，原请求被拒', async () => {
    setTokens({ accessToken: 'old-access', refreshToken: 'rt' })
    const fired = vi.fn()
    window.addEventListener(AUTH_CLEARED_EVENT, fired)
    install(async (cfg) => {
      throw httpError(401, 'AUTH_INVALID', 'refresh 已失效', cfg)
    })

    await expect(authApi.me()).rejects.toBeDefined()
    expect(getAccessToken()).toBeNull()
    expect(fired).toHaveBeenCalledTimes(1)
    window.removeEventListener(AUTH_CLEARED_EVENT, fired)
  })

  it('并发 401 只触发一次 refresh（共享 in-flight promise）', async () => {
    setTokens({ accessToken: 'old-access', refreshToken: 'rt' })
    let refreshCalls = 0
    let meAttempts = 0
    install(async (cfg) => {
      if (cfg.url === '/auth/refresh') {
        refreshCalls += 1
        // 略微放慢 refresh，确保两个并发 401 都先到达再开始刷新，验证合并。
        await new Promise((r) => setTimeout(r, 20))
        return ok({ code: 0, message: 'ok', data: sessionJson })
      }
      if (cfg.url === '/me') {
        meAttempts += 1
        if (meAttempts <= 2) throw httpError(401, 'AUTH_INVALID', 'expired', cfg)
        return ok({ code: 0, message: 'ok', data: meJson })
      }
      throw httpError(404, 'RESOURCE_NOT_FOUND', 'not found', cfg)
    })

    const [a, b] = await Promise.all([authApi.me(), authApi.me()])
    expect(a.user.username).toBe('alice')
    expect(b.user.username).toBe('alice')
    expect(refreshCalls).toBe(1)
  })
})
