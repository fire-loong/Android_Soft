import { QueryClient } from '@tanstack/react-query'

// 全局默认：失败不静默重试轰炸；弱网/冲突由各 query 显式配置（指导书 17）。
export const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 10_000,
      retry: 1,
      refetchOnWindowFocus: false
    }
  }
})
