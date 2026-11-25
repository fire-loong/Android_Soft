/**
 * REST 核心读接口性能冒烟（指导书 16.1：预发布目标负载下 P95 ≤ 500ms）。
 *
 * 真实链路：注册 → 建立个人空间 → 导入一条通知来源 → 预热 55 条事项 → 循环读取核心接口。
 * 不使用 mock，不跳过检查；阈值不达标时 k6 以非 0 退出码失败。
 *
 * 运行：k6 run tests/performance/api-smoke.js
 * 环境变量：
 *   BASE_URL       后端地址，默认 http://127.0.0.1:8080
 *   PERF_VUS       并发虚拟用户数，默认 2
 *   PERF_DURATION  持续时长，默认 20s
 *
 * 阈值只作用于 scenario=core_reads 的请求，setup() 里的注册/建事项/来源导入
 * 属预热写入，不计入 16.1 的“核心读接口”口径。
 * 注意：k6 的 fail() 只有在 setup/teardown 阶段才会让整轮失败并返回非 0 退出码；
 * VU（default）阶段必须靠 thresholds 兜底，不要在 default() 里依赖 fail() 变红。
 */
import http from 'k6/http'
import { check, fail, sleep } from 'k6'

const BASE_URL = __ENV.BASE_URL || 'http://127.0.0.1:8080'
const API = `${BASE_URL}/api/v1`
const JSON_HEADERS = { 'Content-Type': 'application/json' }
const THINK_TIME_SECONDS = 0.2
const SEED_TASK_COUNT = 55
const PAGE_SIZE = 50

export const options = {
  scenarios: {
    core_reads: {
      executor: 'constant-vus',
      vus: Number(__ENV.PERF_VUS || '2'),
      duration: __ENV.PERF_DURATION || '20s',
      gracefulStop: '5s'
    }
  },
  thresholds: {
    'http_req_failed{scenario:core_reads}': ['rate<0.01'],
    'http_req_duration{scenario:core_reads}': ['p(95)<500', 'p(99)<1000'],
    'checks{scenario:core_reads}': ['rate>0.99']
  }
}

function uniqueSuffix() {
  return `${Date.now()}${Math.floor(Math.random() * 10000)}`
}

export function setup() {
  const suffix = uniqueSuffix()
  const username = `perf${suffix}`.slice(0, 32)
  const password = 'ActionLoop123!'

  const register = http.post(
    `${API}/auth/register`,
    JSON.stringify({
      email: `${username}@perf.test`,
      username,
      password,
      displayName: '性能冒烟',
      timezone: 'Asia/Shanghai',
      locale: 'zh-CN',
      deviceName: 'k6-perf',
      devicePlatform: 'WEB'
    }),
    { headers: JSON_HEADERS, tags: { phase: 'setup' } }
  )
  if (register.status !== 200 && register.status !== 201) {
    fail(`注册失败 status=${register.status} body=${register.body}`)
  }
  const token = register.json('data.accessToken')
  if (!token) fail('注册响应缺少 accessToken')

  const authorization = { Authorization: `Bearer ${token}` }
  const me = http.get(`${API}/me`, { headers: authorization, tags: { phase: 'setup' } })
  if (me.status !== 200) fail(`GET /me 失败 status=${me.status} body=${me.body}`)

  const workspaces = me.json('data.workspaces') || []
  const workspace = workspaces.find((item) => item.type === 'PERSONAL') || workspaces[0]
  if (!workspace) fail('账号没有可用工作区')

  const share = http.post(
    `${API}/sources/share`,
    JSON.stringify({
      workspaceId: workspace.id,
      sourceType: 'NOTIFICATION',
      title: '性能冒烟：材料提交提醒',
      text: '请于明天 15:00 前提交成绩单和学生证照片',
      sourceKey: `perf-${suffix}`,
      timezone: 'Asia/Shanghai',
      localOnly: true
    }),
    { headers: { ...JSON_HEADERS, ...authorization }, tags: { phase: 'setup' } }
  )
  if (share.status !== 200 && share.status !== 201) {
    fail(`来源导入失败 status=${share.status} body=${share.body}`)
  }

  // 预热事项数据，使 /tasks 首屏分页断言不是空列表恒真（指导书 16.1 千条列表首屏 ≤50）。
  for (let index = 0; index < SEED_TASK_COUNT; index += 1) {
    const created = http.post(
      `${API}/tasks`,
      JSON.stringify({
        workspaceId: workspace.id,
        title: `性能冒烟事项 ${index + 1}`,
        status: 'ACTIVE'
      }),
      { headers: { ...JSON_HEADERS, ...authorization }, tags: { phase: 'setup' } }
    )
    if (created.status !== 200 && created.status !== 201) {
      fail(`预热事项失败 index=${index} status=${created.status} body=${created.body}`)
    }
  }

  return { token, workspaceId: workspace.id }
}

function jsonContentLength(response) {
  const contentType = response.headers['Content-Type'] || ''
  if (response.status !== 200 || !contentType.includes('application/json')) return -1
  try {
    const content = response.json('data.content')
    return Array.isArray(content) ? content.length : -1
  } catch (error) {
    return -1
  }
}

export default function (data) {
  const headers = { Authorization: `Bearer ${data.token}` }

  const me = http.get(`${API}/me`, { headers, tags: { endpoint: 'me' } })
  check(me, { 'GET /me 返回 200': (response) => response.status === 200 })

  const tasks = http.get(`${API}/tasks?workspaceId=${data.workspaceId}&page=0&size=${PAGE_SIZE}`, {
    headers,
    tags: { endpoint: 'tasks' }
  })
  check(tasks, {
    'GET /tasks 返回 200': (response) => response.status === 200,
    [`事项首屏分页返回 ${PAGE_SIZE} 条`]: (response) => jsonContentLength(response) === PAGE_SIZE
  })

  const candidates = http.get(`${API}/inbox/candidates?workspaceId=${data.workspaceId}&limit=20`, {
    headers,
    tags: { endpoint: 'candidates' }
  })
  check(candidates, {
    'GET /inbox/candidates 返回 200': (response) => response.status === 200,
    '候选列表结构为 items 数组': (response) => {
      try {
        return Array.isArray(response.json('data.items'))
      } catch (error) {
        return false
      }
    }
  })

  // 模拟用户阅读间隔，避免以纯压测方式打满本机单实例。
  sleep(THINK_TIME_SECONDS)
}
