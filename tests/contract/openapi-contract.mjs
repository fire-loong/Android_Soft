#!/usr/bin/env node
/**
 * OpenAPI 契约检查（指导书 13.1 / 18.3、WP01）：
 * 1) 与已提交基线 docs/openapi/actionloop-api.json 比较，禁止删除路径、方法、必填字段、枚举值与媒体类型；
 * 2) 校验五能力关键端点仍然存在；
 * 3) 校验 Web 客户端 apps/web/src 中调用的端点都在契约中声明，防止三端各自漂移。
 *
 * 用法：
 *   node tests/contract/openapi-contract.mjs             # 从运行中的后端拉取 /v3/api-docs 并比较
 *   node tests/contract/openapi-contract.mjs --update    # 显式更新基线（必须先人工审查 diff）
 *   CONTRACT_OPENAPI_FILE=path/to/openapi.json           # 用本地文件代替 HTTP（离线复核）
 *   CONTRACT_OPENAPI_URL=http://host/v3/api-docs         # 覆盖默认地址
 *
 * 退出码：0 = 契约兼容；1 = 检测到破坏性变更或客户端漂移；2 = 无法读取契约。
 */
import { readFile, readdir, writeFile, mkdir } from 'node:fs/promises'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..')
const BASELINE_PATH = path.join(ROOT, 'docs/openapi/actionloop-api.json')
const WEB_SRC = path.join(ROOT, 'apps/web/src')
const DEFAULT_URL = 'http://127.0.0.1:8080/v3/api-docs'
const HTTP_METHODS = new Set(['get', 'post', 'put', 'patch', 'delete'])

/** 五能力代表端点：删除任意一个都视为破坏性变更。 */
const REQUIRED_OPERATIONS = [
  ['post', '/api/v1/sources/share'],
  ['post', '/api/v1/sources/{sourceId}/recheck'],
  ['post', '/api/v1/inbox/candidates/{candidateId}/confirm'],
  ['post', '/api/v1/tasks'],
  ['post', '/api/v1/tasks/{taskId}/steps'],
  ['post', '/api/v1/steps/{stepId}/completions'],
  ['post', '/api/v1/completions/{completionId}/review'],
  ['get', '/api/v1/memory/search'],
  ['get', '/api/v1/memory/{documentId}/context'],
  ['post', '/api/v1/processes'],
  ['post', '/api/v1/assist/sessions'],
  ['post', '/api/v1/assist/sessions/{sessionId}/signal']
]

async function loadSpec() {
  if (process.env.CONTRACT_OPENAPI_FILE) {
    const raw = await readFile(path.resolve(ROOT, process.env.CONTRACT_OPENAPI_FILE), 'utf8')
    return JSON.parse(raw)
  }
  const url = process.env.CONTRACT_OPENAPI_URL ?? DEFAULT_URL
  let response
  try {
    response = await fetch(url, { signal: AbortSignal.timeout(30_000) })
  } catch (error) {
    throw new Error(`GET ${url} 失败：${error.message}`)
  }
  if (!response.ok) throw new Error(`GET ${url} 返回 ${response.status}`)
  return response.json()
}

function deref(spec, node, depth = 0) {
  if (!node || typeof node !== 'object' || depth > 20) return node
  if (typeof node.$ref === 'string' && node.$ref.startsWith('#/')) {
    let current = spec
    for (const part of node.$ref.slice(2).split('/')) {
      current = current?.[part]
      if (current == null) return node
    }
    return deref(spec, current, depth + 1)
  }
  return node
}

function typeOf(schema) {
  const type = schema?.type
  return Array.isArray(type) ? type.join('|') : type
}

function diffSchema(baseSpec, currentSpec, baseNode, currentNode, where, issues, requestMode = false, depth = 0) {
  if (depth > 30) return
  const base = deref(baseSpec, baseNode)
  const current = deref(currentSpec, currentNode)
  if (!base || !current) return

  const baseType = typeOf(base)
  const currentType = typeOf(current)
  if (baseType && currentType && baseType !== currentType) {
    issues.push(`${where}: 类型由 ${baseType} 变为 ${currentType}`)
  }

  // 请求方向：新增必填字段会让旧客户端请求被拒绝。响应方向新增必填不影响旧客户端。
  if (requestMode) {
    const baseRequired = new Set(Array.isArray(base.required) ? base.required : [])
    const addedRequired = (Array.isArray(current.required) ? current.required : []).filter(
      (name) => !baseRequired.has(name)
    )
    if (addedRequired.length > 0) {
      issues.push(`${where}: 新增必填字段 [${addedRequired.join(', ')}]`)
    }
  }

  // 当前整体去掉 enum 属于放宽约束，旧客户端仍可用，不算破坏性变更。
  if (Array.isArray(base.enum) && Array.isArray(current.enum)) {
    const remaining = new Set(current.enum)
    const removed = base.enum.filter((value) => !remaining.has(value))
    if (removed.length > 0) issues.push(`${where}: 枚举值被移除 [${removed.join(', ')}]`)
  }

  const baseProps = base.properties ?? {}
  const currentProps = current.properties ?? {}
  for (const name of Object.keys(baseProps)) {
    if (!(name in currentProps)) {
      issues.push(`${where}.${name}: 字段被移除`)
      continue
    }
    diffSchema(baseSpec, currentSpec, baseProps[name], currentProps[name], `${where}.${name}`, issues, requestMode, depth + 1)
  }

  if (base.items && current.items) {
    diffSchema(baseSpec, currentSpec, base.items, current.items, `${where}[]`, issues, requestMode, depth + 1)
  } else if (base.items && !current.items) {
    issues.push(`${where}: items 被移除`)
  }

  for (const combinator of ['allOf', 'oneOf', 'anyOf']) {
    if (!Array.isArray(base[combinator])) continue
    const currentList = Array.isArray(current[combinator]) ? current[combinator] : []
    if (currentList.length === 0) {
      issues.push(`${where}: ${combinator} 被移除`)
      continue
    }
    const baseRefs = base[combinator].map((entry) => (typeof entry?.$ref === 'string' ? entry.$ref : null))
    const currentRefs = currentList.map((entry) => (typeof entry?.$ref === 'string' ? entry.$ref : null))
    if (baseRefs.every(Boolean) && currentRefs.every(Boolean)) {
      // 全部是 $ref 时按引用名对齐，避免组合顺序变化造成误报、漏项造成漏报。
      const currentByRef = new Map(currentRefs.map((ref, index) => [ref, currentList[index]]))
      baseRefs.forEach((ref, index) => {
        const matched = currentByRef.get(ref)
        if (!matched) {
          issues.push(`${where}: ${combinator} 缺少 ${ref}`)
          return
        }
        diffSchema(baseSpec, currentSpec, base[combinator][index], matched, `${where}.${combinator}(${ref})`, issues, requestMode, depth + 1)
      })
    } else {
      if (currentList.length < base[combinator].length) {
        issues.push(`${where}: ${combinator} 组合项由 ${base[combinator].length} 减为 ${currentList.length}`)
      }
      base[combinator].slice(0, currentList.length).forEach((entry, index) => {
        diffSchema(baseSpec, currentSpec, entry, currentList[index], `${where}.${combinator}[${index}]`, issues, requestMode, depth + 1)
      })
    }
  }
}

function compareParameters(baseSpec, currentSpec, baseOp, currentOp, where, issues) {
  const index = new Map()
  for (const rawParameter of currentOp.parameters ?? []) {
    const parameter = deref(currentSpec, rawParameter)
    index.set(`${parameter.in}:${parameter.name}`, parameter)
  }
  const baseIndex = new Map()
  for (const rawParameter of baseOp.parameters ?? []) {
    const parameter = deref(baseSpec, rawParameter)
    baseIndex.set(`${parameter.in}:${parameter.name}`, parameter)
  }
  for (const [key, parameter] of index) {
    if (parameter.required && !baseIndex.has(key)) {
      issues.push(`${where}: 新增必填参数 ${key}`)
    }
  }
  for (const rawParameter of baseOp.parameters ?? []) {
    const parameter = deref(baseSpec, rawParameter)
    const key = `${parameter.in}:${parameter.name}`
    const matched = index.get(key)
    if (!matched) {
      issues.push(`${where}: 参数 ${key} 被移除`)
      continue
    }
    if (!parameter.required && matched.required) {
      issues.push(`${where}: 参数 ${key} 由可选变为必填`)
    }
    diffSchema(baseSpec, currentSpec, parameter.schema, matched.schema, `${where} 参数 ${key}`, issues, true)
  }
}

function compareRequestBody(baseSpec, currentSpec, baseOp, currentOp, where, issues) {
  const baseBody = deref(baseSpec, baseOp.requestBody)
  const currentBody = deref(currentSpec, currentOp.requestBody)
  if (!baseBody) {
    if (currentBody?.required) issues.push(`${where}: 新增必填请求体`)
    return
  }
  if (!currentBody) {
    issues.push(`${where}: 请求体被移除`)
    return
  }
  if (!baseBody.required && currentBody.required) {
    issues.push(`${where}: 请求体由可选变为必填`)
  }
  const baseContent = baseBody.content ?? {}
  const currentContent = currentBody.content ?? {}
  for (const mediaType of Object.keys(baseContent)) {
    const currentMedia = currentContent[mediaType]
    if (!currentMedia) {
      issues.push(`${where}: 请求媒体类型 ${mediaType} 被移除`)
      continue
    }
    diffSchema(
      baseSpec,
      currentSpec,
      baseContent[mediaType].schema,
      currentMedia.schema,
      `${where} 请求 ${mediaType}`,
      issues,
      true
    )
  }
}

function compareResponses(baseSpec, currentSpec, baseOp, currentOp, where, issues) {
  const baseResponses = baseOp.responses ?? {}
  const currentResponses = currentOp.responses ?? {}
  for (const status of Object.keys(baseResponses)) {
    const baseResponse = deref(baseSpec, baseResponses[status])
    const currentResponse = deref(currentSpec, currentResponses[status])
    if (!currentResponse) {
      issues.push(`${where}: 响应 ${status} 被移除`)
      continue
    }
    const baseContent = baseResponse.content ?? {}
    const currentContent = currentResponse.content ?? {}
    for (const mediaType of Object.keys(baseContent)) {
      if (!currentContent[mediaType]) {
        issues.push(`${where}: 响应 ${status} 媒体类型 ${mediaType} 被移除`)
        continue
      }
      diffSchema(
        baseSpec,
        currentSpec,
        baseContent[mediaType].schema,
        currentContent[mediaType].schema,
        `${where} 响应 ${status} ${mediaType}`,
        issues
      )
    }
  }
}

function compareSpecs(baseSpec, currentSpec) {
  const issues = []

  for (const [method, apiPath] of REQUIRED_OPERATIONS) {
    if (!currentSpec.paths?.[apiPath]?.[method]) {
      issues.push(`关键端点缺失: ${method.toUpperCase()} ${apiPath}`)
    }
  }

  for (const [apiPath, baseItem] of Object.entries(baseSpec.paths ?? {})) {
    const currentItem = currentSpec.paths?.[apiPath]
    if (!currentItem) {
      issues.push(`路径被移除: ${apiPath}`)
      continue
    }
    for (const method of Object.keys(baseItem)) {
      if (!HTTP_METHODS.has(method)) continue
      const baseOp = baseItem[method]
      const currentOp = currentItem[method]
      const where = `${method.toUpperCase()} ${apiPath}`
      if (!currentOp) {
        issues.push(`方法被移除: ${where}`)
        continue
      }
      compareParameters(baseSpec, currentSpec, baseOp, currentOp, where, issues)
      compareRequestBody(baseSpec, currentSpec, baseOp, currentOp, where, issues)
      compareResponses(baseSpec, currentSpec, baseOp, currentOp, where, issues)
    }
  }
  return issues
}

async function walk(dir) {
  const entries = await readdir(dir, { withFileTypes: true })
  const files = []
  for (const entry of entries) {
    const full = path.join(dir, entry.name)
    if (entry.isDirectory()) files.push(...(await walk(full)))
    else if (/\.(ts|tsx)$/.test(entry.name) && !/\.test\.(ts|tsx)$/.test(entry.name)) files.push(full)
  }
  return files
}

function extractClientCalls(source) {
  const calls = []
  const pattern = /\bapi\.(get|post|put|patch|delete)\b/g
  let match
  while ((match = pattern.exec(source))) {
    const open = source.indexOf('(', match.index + match[0].length)
    if (open < 0) continue
    let cursor = open + 1
    while (cursor < source.length && /\s/.test(source[cursor])) cursor += 1
    const quote = source[cursor]
    if (quote !== "'" && quote !== '"' && quote !== '`') continue
    cursor += 1
    let literal = ''
    while (cursor < source.length && source[cursor] !== quote) {
      if (source[cursor] === '\\') {
        literal += source[cursor + 1] ?? ''
        cursor += 2
        continue
      }
      literal += source[cursor]
      cursor += 1
    }
    if (!literal.startsWith('/')) continue
    calls.push({ method: match[1], literal })
    pattern.lastIndex = cursor
  }
  return calls
}

function normalizePath(literal) {
  return `/api/v1${literal}`
    .replace(/\$\{[^}]*\}/g, '{}')
    .replace(/\{[^}]*\}/g, '{}')
    .replace(/\/+$/, '')
}

async function checkWebClient(spec) {
  const index = new Map()
  for (const [apiPath, item] of Object.entries(spec.paths ?? {})) {
    index.set(normalizePath(apiPath.slice('/api/v1'.length)), new Set(Object.keys(item).filter((m) => HTTP_METHODS.has(m))))
  }

  const issues = []
  let scanned = 0
  for (const file of await walk(WEB_SRC)) {
    const source = await readFile(file, 'utf8')
    for (const call of extractClientCalls(source)) {
      scanned += 1
      const key = normalizePath(call.literal)
      const methods = index.get(key)
      const relative = path.relative(ROOT, file)
      if (!methods) {
        issues.push(`${relative}: 客户端调用 ${call.literal} 在 OpenAPI 中不存在`)
        continue
      }
      if (methods.size > 0 && !methods.has(call.method)) {
        issues.push(`${relative}: ${call.method.toUpperCase()} ${call.literal} 未在契约中声明`)
      }
    }
  }
  return { issues, scanned }
}

async function main() {
  const update = process.argv.includes('--update')
  const spec = await loadSpec()
  if (!spec.paths || Object.keys(spec.paths).length === 0) {
    throw new Error('契约不含任何 paths，拒绝继续')
  }

  if (update) {
    const missing = REQUIRED_OPERATIONS.filter(([method, apiPath]) => !spec.paths?.[apiPath]?.[method])
    if (missing.length > 0) {
      console.error('拒绝更新基线：契约缺少五能力关键端点：')
      for (const [method, apiPath] of missing) console.error(`- ${method.toUpperCase()} ${apiPath}`)
      process.exitCode = 1
      return
    }
    // servers.url 由请求地址动态生成（127.0.0.1/localhost 会不同），不属于契约内容，写基线前去掉。
    delete spec.servers
    await mkdir(path.dirname(BASELINE_PATH), { recursive: true })
    await writeFile(BASELINE_PATH, `${JSON.stringify(spec, null, 2)}\n`, 'utf8')
    console.log(`已更新基线：${path.relative(ROOT, BASELINE_PATH)}（${Object.keys(spec.paths).length} 个路径）`)
    console.log('请人工审查 git diff，确认每处变更都是有意且兼容的。')
    return
  }

  const baseSpec = JSON.parse(await readFile(BASELINE_PATH, 'utf8'))
  const issues = compareSpecs(baseSpec, spec)
  const web = await checkWebClient(spec)

  console.log(`基线路径 ${Object.keys(baseSpec.paths ?? {}).length}，当前路径 ${Object.keys(spec.paths).length}`)
  console.log(`扫描 Web 客户端调用 ${web.scanned} 处`)

  const all = [...issues, ...web.issues]
  if (all.length > 0) {
    console.error(`\n契约检查失败，共 ${all.length} 项：`)
    for (const issue of all.slice(0, 100)) console.error(`- ${issue}`)
    if (all.length > 100) console.error(`... 其余 ${all.length - 100} 项省略`)
    process.exitCode = 1
    return
  }
  console.log('契约兼容：无路径/方法/字段/枚举移除，Web 客户端端点全部在契约中声明。')
}

main().catch((error) => {
  console.error(`契约检查无法执行：${error.message}`)
  process.exitCode = 2
})
