import assert from 'node:assert/strict'
import { test } from 'node:test'
import { ApiError, createApiClient, unwrap } from '../dist/index.js'

function jsonResponse(body, status = 200, headers = {}) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'content-type': 'application/json', ...headers }
  })
}

test('替换路径参数、序列化 query、注入 Bearer 与 JSON 请求体', async () => {
  const calls = []
  const client = createApiClient({
    baseUrl: 'http://api.test/',
    getAccessToken: () => 'token-123',
    fetch: async (url, init) => {
      calls.push({ url, init })
      return jsonResponse({ data: { ok: true } })
    }
  })

  const response = await client.request('GET /api/v1/tasks', {
    query: { workspaceId: 'w-1', page: 0, size: 50, status: undefined }
  })

  assert.deepEqual(response, { data: { ok: true } })
  assert.equal(calls[0].url, 'http://api.test/api/v1/tasks?workspaceId=w-1&page=0&size=50')
  assert.equal(calls[0].init.method, 'GET')
  assert.equal(calls[0].init.headers.Authorization, 'Bearer token-123')
  assert.equal(calls[0].init.body, undefined)
})

test('路径参数缺失时抛出带参数名的错误', async () => {
  const client = createApiClient({
    baseUrl: 'http://api.test',
    fetch: async () => jsonResponse({})
  })

  await assert.rejects(
    () => client.request('GET /api/v1/tasks/{id}', { query: { workspaceId: 'w-1' } }),
    /缺少路径参数：id/
  )
})

test('非 2xx 抛出 ApiError，保留状态码、响应体与 X-Request-Id', async () => {
  const client = createApiClient({
    baseUrl: 'http://api.test',
    fetch: async () =>
      jsonResponse({ error: { code: 'PERMISSION_DENIED' } }, 403, { 'x-request-id': 'req-9' })
  })

  await assert.rejects(
    () => client.request('GET /api/v1/me'),
    (error) => {
      assert.ok(error instanceof ApiError)
      assert.equal(error.status, 403)
      assert.equal(error.requestId, 'req-9')
      assert.deepEqual(error.body, { error: { code: 'PERMISSION_DENIED' } })
      return true
    }
  )
})

test('unwrap 取 data，缺失时抛错', () => {
  assert.deepEqual(unwrap({ data: { id: 'x' } }), { id: 'x' })
  assert.throws(() => unwrap({}), /缺少 data/)
  assert.throws(() => unwrap(null), /缺少 data/)
})
