// token 存取：纯模块，无 React 依赖，供 api.ts 与 auth.tsx 共用，避免循环引用。
// refreshToken 落 localStorage 以支持刷新恢复；accessToken 同样持久化（演示用途）。
const ACCESS_KEY = 'actionloop.accessToken'
const REFRESH_KEY = 'actionloop.refreshToken'

export interface TokenPair {
  accessToken: string
  refreshToken: string
}

export function getAccessToken(): string | null {
  return localStorage.getItem(ACCESS_KEY)
}

export function getRefreshToken(): string | null {
  return localStorage.getItem(REFRESH_KEY)
}

export function setTokens(pair: TokenPair): void {
  localStorage.setItem(ACCESS_KEY, pair.accessToken)
  localStorage.setItem(REFRESH_KEY, pair.refreshToken)
}

export function clearTokens(): void {
  localStorage.removeItem(ACCESS_KEY)
  localStorage.removeItem(REFRESH_KEY)
}

export function isAuthenticated(): boolean {
  return !!getRefreshToken()
}
