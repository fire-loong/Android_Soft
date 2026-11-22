// 认证失效事件总线：api.ts 的 refresh 分支清 token 后派发，auth.tsx 监听以同步 React 态。
// 独立模块以打破 api.ts ↔ auth.tsx 的循环依赖。
export const AUTH_CLEARED_EVENT = 'actionloop:auth-cleared'

export function notifyAuthCleared(): void {
  window.dispatchEvent(new CustomEvent(AUTH_CLEARED_EVENT))
}
