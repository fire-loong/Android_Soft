#!/usr/bin/env node
/**
 * 跨客户端一致性场景（指导书 17.3：D-02 越权、D-04 并发冲突、D-05 幂等；17.5 准出证据）。
 *
 * 诚实边界：这是**同一后端、多客户端身份的 API 层联调**，用于验证三端共享的服务端语义；
 * 不等同于 Web + Android 真机同场景联跑（真机联测仍需设备，见 docs/competition/acceptance-matrix.md）。
 *
 * 前置：真实 API（默认 http://127.0.0.1:8080），注册限流需关闭（ACTIONLOOP_RL_REGISTER_MAX=0）。
 * 用法：node tests/joint/cross-client-consistency.mjs
 * 退出码：0 = 全部场景通过；1 = 有断言失败。
 */
import assert from 'node:assert/strict'

const BASE_URL = process.env.JOINT_BASE_URL || 'http://127.0.0.1:8080'
const API = `${BASE_URL}/api/v1`
const suffix = `${Date.now()}${Math.floor(Math.random() * 1000)}`
const PASSWORD = 'ActionLoop123!'

const results = []

async function http(method, path, { token, body, headers = {} } = {}) {
  const init = { method, headers: { ...headers } }
  if (token) init.headers.Authorization = `Bearer ${token}`
  if (body !== undefined) {
    init.headers['Content-Type'] = 'application/json'
    init.body = JSON.stringify(body)
  }
  const response = await fetch(`${API}${path}`, init)
  const text = await response.text()
  let payload
  try {
    payload = text ? JSON.parse(text) : undefined
  } catch {
    payload = text
  }
  return { status: response.status, body: payload, text }
}

async function register(role, devicePlatform) {
  const response = await http('POST', '/auth/register', {
    body: {
      email: `joint-${role}-${suffix}@joint.test`,
      username: `joint${role}${suffix}`.slice(0, 32),
      password: PASSWORD,
      displayName: `联合验收${role}`,
      timezone: 'Asia/Shanghai',
      locale: 'zh-CN',
      deviceName: `joint-${role}`,
      devicePlatform
    }
  })
  assert.ok([200, 201].includes(response.status), `注册 ${role} 失败：${response.status} ${response.text}`)
  return response.body.data.accessToken
}

async function personalWorkspace(token) {
  const me = await http('GET', '/me', { token })
  assert.equal(me.status, 200, `GET /me 失败：${me.status}`)
  const workspace = (me.body.data.workspaces || []).find((item) => item.type === 'PERSONAL')
  assert.ok(workspace, '账号缺少 PERSONAL 工作区')
  return workspace.id
}

async function scenario(name, fn) {
  try {
    await fn()
    results.push({ name, ok: true, detail: '' })
  } catch (error) {
    results.push({ name, ok: false, detail: error.message })
  }
}

async function main() {
  const webToken = await register('web', 'WEB')
  const androidToken = await register('android', 'ANDROID')
  const webWorkspace = await personalWorkspace(webToken)
  const androidWorkspace = await personalWorkspace(androidToken)

  let task
  let initialVersion
  // 场景 1：Web 端建事项
  await scenario('Web 建事项并拿到初始版本', async () => {
    const created = await http('POST', '/tasks', {
      token: webToken,
      body: { workspaceId: webWorkspace, title: '联合验收：提交成绩单与学生证' }
    })
    assert.ok([200, 201].includes(created.status), `创建失败：${created.status} ${created.text}`)
    task = created.body.data
    initialVersion = task.version
    assert.equal(typeof initialVersion, 'number', `初始版本字段缺失：${created.text}`)
  })

  if (!task) {
    report()
    return
  }

  await scenario('Android 身份读取他人事项被拒且不泄露标题', async () => {
    const denied = await http('GET', `/tasks/${task.id}?workspaceId=${webWorkspace}`, { token: androidToken })
    assert.ok([403, 404].includes(denied.status), `越权读取未被拒绝：${denied.status}`)
    assert.ok(
      !JSON.stringify(denied.body ?? '').includes('联合验收'),
      '越权响应泄露了事项标题'
    )
  })

  await scenario('Web 端状态转换 ACTIVE→IN_PROGRESS 版本递增', async () => {
    const moved = await http('POST', `/tasks/${task.id}/transitions?workspaceId=${webWorkspace}`, {
      token: webToken,
      body: { status: 'IN_PROGRESS', baseVersion: task.version }
    })
    assert.equal(moved.status, 200, `状态转换失败：${moved.status} ${moved.text}`)
    assert.equal(moved.body.data.status, 'IN_PROGRESS')
    assert.equal(moved.body.data.version, initialVersion + 1, `版本应递增到 ${initialVersion + 1}，实际 ${moved.body.data.version}`)
    task = moved.body.data
  })

  await scenario('旧 baseVersion 并发写入返回版本冲突而非静默覆盖', async () => {
    const stale = await http('POST', `/tasks/${task.id}/transitions?workspaceId=${webWorkspace}`, {
      token: webToken,
      body: { status: 'COMPLETED', baseVersion: initialVersion }
    })
    assert.equal(stale.status, 409, `期望 409，实际 ${stale.status} ${stale.text}`)
  })

  await scenario('同 Idempotency-Key 重放不重复建事项', async () => {
    const key = `joint-${suffix}-idem`
    const body = { workspaceId: androidWorkspace, title: '联合验收：幂等创建' }
    const first = await http('POST', '/tasks', {
      token: androidToken,
      body,
      headers: { 'Idempotency-Key': key }
    })
    assert.ok([200, 201].includes(first.status), `首次创建失败：${first.status} ${first.text}`)
    const replay = await http('POST', '/tasks', {
      token: androidToken,
      body,
      headers: { 'Idempotency-Key': key }
    })
    assert.equal(replay.status, first.status, `重放状态码不一致：${replay.status}`)
    assert.equal(replay.body.data.id, first.body.data.id, '重放产生了不同的事项 ID')

    const list = await http('GET', `/tasks?workspaceId=${androidWorkspace}&page=0&size=50`, {
      token: androidToken
    })
    const matched = (list.body.data.content || []).filter((item) => item.title === '联合验收：幂等创建')
    assert.equal(matched.length, 1, `幂等标题应只有 1 条，实际 ${matched.length}`)
  })

  await scenario('Android 身份读取自己的事项成功（跨端可读同一契约）', async () => {
    const own = await http('GET', `/tasks?workspaceId=${androidWorkspace}&page=0&size=50`, {
      token: androidToken
    })
    assert.equal(own.status, 200)
    assert.ok(Array.isArray(own.body.data.content), '列表结构应为 data.content 数组')
  })

  report()
}

function report() {
  const failed = results.filter((item) => !item.ok)
  console.log('跨客户端一致性场景（API 层，非真机联测）：')
  for (const item of results) {
    console.log(` ${item.ok ? 'PASS' : 'FAIL'}  ${item.name}${item.ok ? '' : ` —— ${item.detail}`}`)
  }
  console.log(`合计 ${results.length} 个场景，通过 ${results.length - failed.length}，失败 ${failed.length}`)
  if (failed.length > 0) process.exit(1)
}

main().catch((error) => {
  console.error(`场景执行异常：${error.message}`)
  process.exit(1)
})
