#!/usr/bin/env node
/**
 * AsyncAPI 实时契约检查（指导书 674 行：三端契约含异步事件；724 行：contracts/asyncapi）。
 *
 * 真实契约来源：contracts/asyncapi/actionloop-realtime.json，对应后端
 * WorkspaceEventMessage（/ws/events）与 AssistEventView（/ws/assist/{sessionId}）。
 *
 * 校验内容：
 * 1) 文件可解析且为 AsyncAPI 3.x；
 * 2) 关键 channel、operation、message 仍然存在（删除即阻断）；
 * 3) 文档内所有本地 $ref 都能解析（避免断链）；
 * 4) 关键事件字段仍在 required 中（字段被删会改变客户端补洞语义）。
 *
 * 用法：node tests/contract/asyncapi-contract.mjs
 * 退出码：0 = 通过；1 = 契约破坏；2 = 无法读取或解析。
 */
import { readFile } from 'node:fs/promises'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..')
const SPEC_PATH = path.join(ROOT, 'contracts/asyncapi/actionloop-realtime.json')

const REQUIRED_CHANNELS = {
  workspaceEvents: '/ws/events',
  assistSession: '/ws/assist/{sessionId}'
}

const REQUIRED_OPERATIONS = ['receiveWorkspaceEvent', 'sendPing', 'receivePong', 'receiveAssistEvent']

const REQUIRED_MESSAGES = ['WorkspaceEvent', 'AssistEvent', 'Ping', 'Pong']

const REQUIRED_SCHEMA_FIELDS = {
  WorkspaceEventPayload: ['workspaceId', 'seq', 'aggregateId', 'aggregateType', 'eventType', 'createdAt'],
  AssistEventView: ['sessionId', 'seq', 'eventType', 'createdAt']
}

function resolveRef(spec, ref) {
  if (!ref.startsWith('#/')) return undefined
  let current = spec
  for (const part of ref.slice(2).split('/')) {
    current = current?.[part]
    if (current === undefined) return undefined
  }
  return current
}

function collectRefs(node, found = []) {
  if (!node || typeof node !== 'object') return found
  if (typeof node.$ref === 'string') found.push(node.$ref)
  for (const value of Object.values(node)) collectRefs(value, found)
  return found
}

async function main() {
  let spec
  try {
    spec = JSON.parse(await readFile(SPEC_PATH, 'utf8'))
  } catch (error) {
    console.error(`无法读取或解析 ${path.relative(ROOT, SPEC_PATH)}：${error.message}`)
    process.exit(2)
  }

  const issues = []

  if (typeof spec.asyncapi !== 'string' || !spec.asyncapi.startsWith('3.')) {
    issues.push(`asyncapi 版本不是 3.x：${spec.asyncapi}`)
  }

  for (const [name, address] of Object.entries(REQUIRED_CHANNELS)) {
    const channel = spec.channels?.[name]
    if (!channel) {
      issues.push(`channel 缺失：${name}`)
      continue
    }
    if (channel.address !== address) {
      issues.push(`channel ${name} 地址由 ${address} 变为 ${channel.address}`)
    }
  }

  for (const name of REQUIRED_MESSAGES) {
    if (!spec.components?.messages?.[name]) issues.push(`message 缺失：${name}`)
  }

  for (const name of REQUIRED_OPERATIONS) {
    if (!spec.operations?.[name]) issues.push(`operation 缺失：${name}`)
  }

  for (const [name, fields] of Object.entries(REQUIRED_SCHEMA_FIELDS)) {
    const schema = spec.components?.schemas?.[name]
    if (!schema) {
      issues.push(`schema 缺失：${name}`)
      continue
    }
    const required = new Set(Array.isArray(schema.required) ? schema.required : [])
    const missing = fields.filter((field) => !required.has(field))
    if (missing.length > 0) issues.push(`schema ${name} 缺少必填字段：[${missing.join(', ')}]`)
  }

  for (const ref of collectRefs(spec)) {
    if (resolveRef(spec, ref) === undefined) issues.push(`$ref 无法解析：${ref}`)
  }

  if (issues.length > 0) {
    console.error('AsyncAPI 契约检查失败：')
    for (const issue of issues) console.error(` - ${issue}`)
    process.exit(1)
  }

  const channelCount = Object.keys(spec.channels ?? {}).length
  const messageCount = Object.keys(spec.components?.messages ?? {}).length
  console.log(`AsyncAPI ${spec.asyncapi}；channel ${channelCount} 个、message ${messageCount} 个；结构与引用校验通过。`)
}

main().catch((error) => {
  console.error(error)
  process.exit(2)
})
