/**
 * 三端共享的 TypeScript API 客户端（指导书 674 行 / 719 行）。
 *
 * 类型来自 `src/generated/schema.ts`（由 OpenAPI 基线生成，不手改）；
 * 本文件只提供与框架无关的 fetch 封装：路径参数替换、query 序列化、Bearer 注入与统一错误。
 */
import type { Operations, OperationId } from './generated/schema.js'

export type { Operations, OperationId, Schemas, SchemaName } from './generated/schema.js'

/** 单个操作的入参：路径参数、query、header 与 JSON 请求体。 */
export interface OperationInput<K extends OperationId> {
  path?: Operations[K]['path']
  query?: Operations[K]['query']
  headers?: Operations[K]['headers']
  body?: Operations[K]['body']
}

/** 单个操作的成功响应类型（后端 ApiResponse 信封，data 内为业务对象）。 */
export type OperationResponse<K extends OperationId> = Operations[K]['response']

export interface RequestOptions {
  signal?: AbortSignal
}

/** 统一错误：保留 HTTP 状态、响应体与后端 X-Request-Id，便于三端一致地提示与排查。 */
export class ApiError extends Error {
  readonly status: number
  readonly body: unknown
  readonly requestId: string | null

  constructor(status: number, body: unknown, requestId: string | null) {
    super(`请求失败：HTTP ${status}`)
    this.name = 'ApiError'
    this.status = status
    this.body = body
    this.requestId = requestId
  }
}

export interface ApiClientConfig {
  baseUrl: string
  /** 允许注入 fetch，便于测试与非浏览器环境；缺省使用全局 fetch。 */
  fetch?: typeof globalThis.fetch
  /** 每次请求时读取访问令牌；未登录返回 null/undefined 即不带 Authorization。 */
  getAccessToken?: () => string | null | undefined | Promise<string | null | undefined>
  defaultHeaders?: Record<string, string>
}

export interface ApiClient {
  request<K extends OperationId>(
    operation: K,
    input?: OperationInput<K>,
    options?: RequestOptions
  ): Promise<OperationResponse<K>>
}

/** 从 ApiResponse 信封取 data；缺失时抛错，避免把 undefined 当成业务值继续执行。 */
export function unwrap<T>(payload: { data?: T } | null | undefined): T {
  if (!payload || payload.data === undefined || payload.data === null) {
    throw new Error('响应缺少 data 字段')
  }
  return payload.data
}

function splitOperation(operation: string): { method: string; pathTemplate: string } {
  const separator = operation.indexOf(' ')
  return {
    method: operation.slice(0, separator),
    pathTemplate: operation.slice(separator + 1)
  }
}

function buildUrl(baseUrl: string, operation: string, input: { path?: unknown; query?: unknown }): string {
  const { pathTemplate } = splitOperation(operation)
  const pathParams = (input.path ?? {}) as Record<string, unknown>
  const apiPath = pathTemplate.replace(/\{([^}]+)\}/g, (_match, name: string) => {
    const value = pathParams[name]
    if (value === undefined || value === null || value === '') {
      throw new Error(`缺少路径参数：${name}`)
    }
    return encodeURIComponent(String(value))
  })

  const search = new URLSearchParams()
  const query = (input.query ?? {}) as Record<string, unknown>
  for (const [name, value] of Object.entries(query)) {
    if (value === undefined || value === null) continue
    if (Array.isArray(value)) {
      for (const item of value) if (item !== undefined && item !== null) search.append(name, String(item))
      continue
    }
    search.append(name, typeof value === 'object' ? JSON.stringify(value) : String(value))
  }
  const queryString = search.toString()
  return `${baseUrl}${apiPath}${queryString ? `?${queryString}` : ''}`
}

async function parseBody(response: Response): Promise<unknown> {
  if (response.status === 204) return undefined
  const contentType = response.headers.get('content-type') ?? ''
  const text = await response.text()
  if (!text) return undefined
  if (contentType.includes('json')) {
    try {
      return JSON.parse(text)
    } catch {
      return text
    }
  }
  return text
}

export function createApiClient(config: ApiClientConfig): ApiClient {
  const baseUrl = config.baseUrl.replace(/\/+$/, '')
  const doFetch = config.fetch ?? globalThis.fetch
  if (typeof doFetch !== 'function') {
    throw new Error('当前环境缺少 fetch，请通过 config.fetch 注入')
  }

  return {
    async request<K extends OperationId>(operation: K, input: OperationInput<K> = {}, options: RequestOptions = {}) {
      const headers: Record<string, string> = { ...(config.defaultHeaders ?? {}) }
      for (const [name, value] of Object.entries((input.headers ?? {}) as Record<string, unknown>)) {
        if (value !== undefined && value !== null) headers[name] = String(value)
      }

      const token = await config.getAccessToken?.()
      if (token) headers.Authorization = `Bearer ${token}`

      let body: string | undefined
      if (input.body !== undefined && input.body !== null) {
        headers['Content-Type'] = headers['Content-Type'] ?? 'application/json'
        body = JSON.stringify(input.body)
      }

      const response = await doFetch(buildUrl(baseUrl, operation, input), {
        method: splitOperation(operation).method,
        headers,
        body,
        signal: options.signal
      })

      const payload = await parseBody(response)
      if (!response.ok) {
        throw new ApiError(response.status, payload, response.headers.get('x-request-id'))
      }
      return payload as OperationResponse<K>
    }
  }
}
