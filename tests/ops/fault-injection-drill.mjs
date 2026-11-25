#!/usr/bin/env node
/**
 * 故障注入演练：杀进程后不丢已确认数据（指导书 17.5「故障注入与备份恢复演练」、17.3 D-05）。
 *
 * 做什么：
 * 1) 用真实 jar 启动后端（独立存储目录、Redis DB 15、限流关闭）；
 * 2) 注册用户 → 用固定 Idempotency-Key 建一条已确认事项；
 * 3) **硬杀** API 进程（Windows taskkill /F，POSIX SIGKILL）；
 * 4) 重启后检查：事项仍在、状态未丢、旧令牌仍有效（同一 JWT 密钥）；
 * 5) 用同一 Idempotency-Key 重放创建：必须返回同一事项 ID，列表只有 1 条。
 *
 * 诚实边界：
 * - 只覆盖「API 进程被杀」，不覆盖数据库重启、宿主机断电、网络分区与 TURN 故障；
 * - 「断网」由客户端连接失败体现，本脚本不断开物理网络；
 * - 需要真实 PG/Redis 与可执行 jar；基础设施不就绪时退出码 2，不伪装成功。
 *
 * 前置：PG 可连、Redis 可连、8080 端口空闲、`services/api/target/actionloop-api.jar` 已构建。
 * 环境变量优先，其次读取仓库根 `.env.local`：
 *   ACTIONLOOP_DB_HOST/PORT/NAME/USER/PASSWORD、ACTIONLOOP_REDIS_HOST/PORT/PASSWORD、
 *   ACTIONLOOP_JWT_SECRET（≥32 字节）、ACTIONLOOP_API_PORT（默认 8080）。
 *   可用 ACTIONLOOP_DRILL_DB_NAME 覆盖库名（默认 actionloop_e2e，避免污染开发库）。
 *
 * 用法：pnpm test:ops:fault
 * 退出码：0 = 全部断言通过；1 = 断言失败；2 = 基础设施未就绪。
 */
import { execFileSync, spawn } from 'node:child_process'
import { createWriteStream, existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { randomUUID } from 'node:crypto'

const ROOT = fileURLToPath(new URL('../..', import.meta.url))
const JAR = join(ROOT, 'services', 'api', 'target', 'actionloop-api.jar')
const OUT_DIR = join(ROOT, 'build', 'ops')
const LOG_DIR = join(OUT_DIR, 'logs')
const RESULT_FILE = join(OUT_DIR, 'fault-injection.json')
const ENV_FILE = join(ROOT, '.env.local')
const IS_WIN = process.platform === 'win32'
const HEALTH_TIMEOUT_MS = 240_000

class InfraError extends Error {}

function log(msg) {
  console.log(msg)
}

function loadDotEnv() {
  const map = {}
  if (!existsSync(ENV_FILE)) return map
  for (const raw of readFileSync(ENV_FILE, 'utf8').split(/\r?\n/)) {
    const line = raw.trim()
    if (!line || line.startsWith('#')) continue
    const idx = line.indexOf('=')
    if (idx <= 0) continue
    let value = line.slice(idx + 1).trim()
    if ((value.startsWith('"') && value.endsWith('"')) || (value.startsWith("'") && value.endsWith("'"))) {
      value = value.slice(1, -1)
    }
    map[line.slice(0, idx).trim()] = value
  }
  return map
}

const dotenv = loadDotEnv()

function env(name, fallback) {
  const fromProcess = process.env[name]
  if (fromProcess !== undefined && fromProcess !== '') return fromProcess
  const fromFile = dotenv[name]
  if (fromFile !== undefined && fromFile !== '') return fromFile
  return fallback
}

function requireEnv(name) {
  const value = env(name)
  if (!value) throw new InfraError(`缺少环境变量 ${name}（process.env 与 .env.local 均未提供）`)
  return value
}

function gitCommit() {
  try {
    return execFileSync('git', ['rev-parse', 'HEAD'], { cwd: ROOT, encoding: 'utf8' }).trim()
  } catch {
    return null
  }
}

const PORT = Number(env('ACTIONLOOP_API_PORT', '8080'))
const BASE_URL = `http://127.0.0.1:${PORT}`
const API = `${BASE_URL}/api/v1`
const PASSWORD = 'ActionLoop123!'
const suffix = `${Date.now()}${Math.floor(Math.random() * 1000)}`

const PLACEHOLDER_MARKERS = ['change_me', 'changeme', 'default_secret']

/**
 * 演练库默认独立（actionloop_e2e），避免污染开发库；CI 用 ACTIONLOOP_DRILL_DB_NAME 覆盖。
 * JWT 密钥在 .env.local 为占位值时生成一次性随机密钥：只用于本次两次启动，
 * 保证「杀进程前后令牌一致」可验证，不写入任何真实配置。
 */
const DRILL_DB_NAME = env('ACTIONLOOP_DRILL_DB_NAME', 'actionloop_e2e')
const DRILL_JWT_SECRET = (() => {
  const configured = env('ACTIONLOOP_JWT_SECRET', '')
  const unusable = !configured || configured.length < 32 || PLACEHOLDER_MARKERS.some((marker) => configured.includes(marker))
  if (unusable) {
    const generated = `${randomUUID().replaceAll('-', '')}${randomUUID().replaceAll('-', '').slice(0, 16)}`
    log('注意：ACTIONLOOP_JWT_SECRET 缺失/占位/过短，演练使用本次随机临时密钥（仅内存，不写入任何配置）')
    return generated
  }
  return configured
})()

function serverEnv() {
  return {
    ...process.env,
    ACTIONLOOP_API_PORT: String(PORT),
    ACTIONLOOP_DB_HOST: requireEnv('ACTIONLOOP_DB_HOST'),
    ACTIONLOOP_DB_PORT: requireEnv('ACTIONLOOP_DB_PORT'),
    ACTIONLOOP_DB_NAME: DRILL_DB_NAME,
    ACTIONLOOP_DB_USER: requireEnv('ACTIONLOOP_DB_USER'),
    ACTIONLOOP_DB_PASSWORD: requireEnv('ACTIONLOOP_DB_PASSWORD'),
    ACTIONLOOP_REDIS_HOST: requireEnv('ACTIONLOOP_REDIS_HOST'),
    ACTIONLOOP_REDIS_PORT: requireEnv('ACTIONLOOP_REDIS_PORT'),
    ACTIONLOOP_REDIS_PASSWORD: env('ACTIONLOOP_REDIS_PASSWORD', ''),
    ACTIONLOOP_JWT_SECRET: DRILL_JWT_SECRET,
    SPRING_DATA_REDIS_DATABASE: '15',
    ACTIONLOOP_AI_PROVIDER: 'deterministic',
    ACTIONLOOP_EMBEDDING_ENABLED: 'false',
    ACTIONLOOP_RERANK_ENABLED: 'false',
    ACTIONLOOP_SEED_DEMO: 'false',
    ACTIONLOOP_STORAGE_LOCAL_ROOT: join(ROOT, '.dev-infra', 'ops', 'storage'),
    ACTIONLOOP_API_DOCS_ENABLED: 'false',
    ACTIONLOOP_RL_REGISTER_MAX: '0',
    ACTIONLOOP_RL_LOGIN_MAX: '0',
    ACTIONLOOP_RL_REFRESH_MAX: '0'
  }
}

async function healthStatus() {
  try {
    const res = await fetch(`${BASE_URL}/actuator/health`, { signal: AbortSignal.timeout(2000) })
    if (!res.ok) return null
    const body = await res.json()
    return body?.status ?? null
  } catch {
    return null
  }
}

function logTail(file, lines = 40) {
  if (!existsSync(file)) return '(无日志)'
  const all = readFileSync(file, 'utf8').split(/\r?\n/).filter(Boolean)
  return all.slice(-lines).join('\n')
}

function startApi(label) {
  const logFile = join(LOG_DIR, `${label}.log`)
  const stream = createWriteStream(logFile, { flags: 'w' })
  const java = env('JAVA_EXE', IS_WIN ? 'java' : 'java')
  const child = spawn(java, ['-jar', JAR], {
    cwd: ROOT,
    env: serverEnv(),
    stdio: ['ignore', 'pipe', 'pipe'],
    shell: false
  })
  child.stdout.pipe(stream)
  child.stderr.pipe(stream)
  child.on('error', (error) => {
    log(`[${label}] 启动失败：${error.message}`)
  })
  return { child, logFile }
}

async function waitForHealth(proc, label) {
  const deadline = Date.now() + HEALTH_TIMEOUT_MS
  while (Date.now() < deadline) {
    if (proc.child.exitCode !== null) {
      throw new InfraError(`[${label}] API 提前退出（code=${proc.child.exitCode}）。日志尾部：\n${logTail(proc.logFile)}`)
    }
    if ((await healthStatus()) === 'UP') return
    await sleep(1500)
  }
  throw new InfraError(`[${label}] API 在 ${HEALTH_TIMEOUT_MS / 1000}s 内未就绪。日志尾部：\n${logTail(proc.logFile)}`)
}

async function waitForPortFree(timeoutMs = 60_000) {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    if ((await healthStatus()) === null) return
    await sleep(500)
  }
  throw new InfraError('杀掉 API 后端口仍在响应')
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms))
}

async function killApi(proc) {
  if (proc.child.exitCode !== null) return
  if (IS_WIN) {
    try {
      execFileSync('taskkill', ['/PID', String(proc.child.pid), '/F', '/T'], { stdio: 'ignore' })
    } catch {
      // 进程可能已退出，继续由下面的端口检查判定
    }
  } else {
    proc.child.kill('SIGKILL')
  }
  const deadline = Date.now() + 30_000
  while (Date.now() < deadline && proc.child.exitCode === null) {
    await sleep(200)
  }
}

async function http(method, path, { token, body, headers = {} } = {}) {
  const init = { method, headers: { ...headers }, signal: AbortSignal.timeout(15_000) }
  if (token) init.headers.Authorization = `Bearer ${token}`
  if (body !== undefined) {
    init.headers['Content-Type'] = 'application/json'
    init.body = JSON.stringify(body)
  }
  const res = await fetch(`${API}${path}`, init)
  const text = await res.text()
  let payload
  try {
    payload = text ? JSON.parse(text) : undefined
  } catch {
    payload = text
  }
  return { status: res.status, body: payload, text }
}

const checks = []
function check(name, fn) {
  return (async () => {
    try {
      await fn()
      checks.push({ name, ok: true })
      log(` PASS  ${name}`)
    } catch (error) {
      checks.push({ name, ok: false, detail: error.message })
      log(` FAIL  ${name} —— ${error.message}`)
    }
  })()
}

function assertOk(condition, message) {
  if (!condition) throw new Error(message)
}

async function main() {
  mkdirSync(LOG_DIR, { recursive: true })
  if (!existsSync(JAR)) throw new InfraError(`找不到 ${JAR}，先执行 cd services && ./mvnw -pl api -am package -DskipTests`)
  if ((await healthStatus()) !== null) {
    throw new InfraError(`端口 ${PORT} 已有 API 在响应；请先停掉手动启动的实例（演练需要独占端口）`)
  }

  const startedAt = new Date().toISOString()
  log('== 故障注入演练：杀进程后不丢已确认数据 ==')
  log(`[1/5] 启动 API（第 1 次），库=${DRILL_DB_NAME}`)
  let first = startApi('api-run-1')
  let second = null
  try {
    await waitForHealth(first, 'run-1')
    log('  API UP')

    log('[2/5] 注册用户并用 Idempotency-Key 建事项')
    const register = await http('POST', '/auth/register', {
      body: {
        email: `drill-${suffix}@drill.test`,
        username: `drill${suffix}`.slice(0, 32),
        password: PASSWORD,
        displayName: '故障注入演练',
        timezone: 'Asia/Shanghai',
        locale: 'zh-CN',
        deviceName: 'drill-node',
        devicePlatform: 'WEB'
      }
    })
    assertOk([200, 201].includes(register.status), `注册失败：${register.status} ${register.text}`)
    const token = register.body.data.accessToken
    const me = await http('GET', '/me', { token })
    assertOk(me.status === 200, `GET /me 失败：${me.status}`)
    const workspace = (me.body.data.workspaces ?? []).find((item) => item.type === 'PERSONAL')
    assertOk(workspace, '账号缺少 PERSONAL 工作区')

    const idempotencyKey = `drill-${suffix}-idem`
    const title = `故障注入：杀进程不丢-${suffix}`
    const createBody = { workspaceId: workspace.id, title }
    const created = await http('POST', '/tasks', {
      token,
      body: createBody,
      headers: { 'Idempotency-Key': idempotencyKey }
    })
    assertOk([200, 201].includes(created.status), `创建事项失败：${created.status} ${created.text}`)
    const taskId = created.body.data.id
    const versionBeforeKill = created.body.data.version
    const statusBeforeKill = created.body.data.status
    log(`  已建事项 ${taskId}（status=${statusBeforeKill}, version=${versionBeforeKill}）`)

    log('[3/5] 硬杀 API 进程')
    const killStart = Date.now()
    await killApi(first)
    await waitForPortFree()
    const killWaitMs = Date.now() - killStart
    log(`  进程已终止，端口释放耗时 ${killWaitMs}ms`)

    log('[4/5] 重启 API（第 2 次）')
    const restartStart = Date.now()
    second = startApi('api-run-2')
    await waitForHealth(second, 'run-2')
    const restartMs = Date.now() - restartStart
    log(`  API 重新 UP，耗时 ${restartMs}ms`)

    log('[5/5] 校验数据与幂等')
    await check('重启后已确认事项仍可读，标题/状态/版本未丢', async () => {
      const got = await http('GET', `/tasks/${taskId}?workspaceId=${workspace.id}`, { token })
      assertOk(got.status === 200, `读取失败：${got.status} ${got.text}`)
      assertOk(got.body.data.title === title, `标题不一致：${got.body.data.title}`)
      assertOk(got.body.data.status === statusBeforeKill, `状态变化：${got.body.data.status} != ${statusBeforeKill}`)
      assertOk(got.body.data.version >= versionBeforeKill, `版本回退：${got.body.data.version} < ${versionBeforeKill}`)
    })

    await check('杀进程前签发的访问令牌在重启后仍有效（同一 JWT 密钥）', async () => {
      const meAgain = await http('GET', '/me', { token })
      assertOk(meAgain.status === 200, `旧令牌失效：${meAgain.status}`)
    })

    await check('同 Idempotency-Key 重放返回同一事项，不重复创建', async () => {
      const replay = await http('POST', '/tasks', {
        token,
        body: createBody,
        headers: { 'Idempotency-Key': idempotencyKey }
      })
      assertOk([200, 201].includes(replay.status), `重放失败：${replay.status} ${replay.text}`)
      assertOk(replay.body.data.id === taskId, `重放产生新事项：${replay.body.data.id} != ${taskId}`)
      const list = await http('GET', `/tasks?workspaceId=${workspace.id}&page=0&size=100`, { token })
      assertOk(list.status === 200, `列表失败：${list.status}`)
      const matched = (list.body.data.content ?? []).filter((item) => item.title === title)
      assertOk(matched.length === 1, `同名事项应只有 1 条，实际 ${matched.length}`)
    })

    writeFileSync(RESULT_FILE, JSON.stringify({
      startedAt,
      finishedAt: new Date().toISOString(),
      commit: gitCommit(),
      port: PORT,
      database: DRILL_DB_NAME,
      taskId,
      killWaitMs,
      restartMs,
      checks
    }, null, 2))

    const failed = checks.filter((item) => !item.ok)
    log(`\n结果：${checks.length - failed.length}/${checks.length} 项通过；报告 ${RESULT_FILE}`)
    if (failed.length > 0) process.exitCode = 1
  } finally {
    if (first) await killApi(first)
    if (second) await killApi(second)
  }
}

main().catch((error) => {
  if (error instanceof InfraError) {
    console.error(`基础设施未就绪：${error.message}`)
    process.exit(2)
  }
  console.error(`演练异常：${error.stack ?? error.message}`)
  process.exit(1)
})
