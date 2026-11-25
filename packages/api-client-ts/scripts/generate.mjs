#!/usr/bin/env node
/**
 * 从已提交的 OpenAPI 基线生成 TypeScript 类型（指导书 674 行 / 719 行：三端契约，`packages/api-client-ts` 为 OpenAPI 生成物，不手改）。
 *
 * 输入：docs/openapi/actionloop-api.json（由 `node tests/contract/openapi-contract.mjs --update` 从真实后端生成）
 * 输出：packages/api-client-ts/src/generated/schema.ts（已被 .gitignore 忽略，CI 每次重新生成）
 *
 * 用法：
 *   node packages/api-client-ts/scripts/generate.mjs            # 生成
 *   node packages/api-client-ts/scripts/generate.mjs --check     # 只校验生成物是否与契约一致（不一致退出 1）
 *
 * 不引入第三方生成器，保证离线可复现；只覆盖本项目契约实际使用的结构
 * （object 属性 / required / enum / array / allOf / oneOf / anyOf / nullable / $ref）。
 */
import { readFile, writeFile, mkdir } from 'node:fs/promises'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const PKG_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const REPO_ROOT = path.resolve(PKG_ROOT, '../..')
const SPEC_PATH = path.join(REPO_ROOT, 'docs/openapi/actionloop-api.json')
const OUT_PATH = path.join(PKG_ROOT, 'src/generated/schema.ts')
const HTTP_METHODS = ['get', 'put', 'post', 'delete', 'patch']

const IDENTIFIER = /^[A-Za-z_$][A-Za-z0-9_$]*$/

function key(name) {
  return IDENTIFIER.test(name) ? name : JSON.stringify(name)
}

function refName(node) {
  const name = node.$ref.split('/').pop()
  if (!name) throw new Error(`无法解析 $ref: ${node.$ref}`)
  return name
}

function indentOf(depth) {
  return '  '.repeat(depth)
}

function renderType(node, depth) {
  if (!node || typeof node !== 'object') return 'unknown'
  if (typeof node.$ref === 'string') return `Schemas[${JSON.stringify(refName(node))}]`

  if (Array.isArray(node.allOf) && node.allOf.length > 0) {
    return node.allOf.map((part) => renderType(part, depth)).join(' & ')
  }
  const union = Array.isArray(node.oneOf) ? node.oneOf : Array.isArray(node.anyOf) ? node.anyOf : null
  if (union && union.length > 0) {
    const rendered = union.map((part) => {
      const inner = renderType(part, depth)
      return inner.includes(' ') ? `(${inner})` : inner
    })
    return rendered.join(' | ')
  }

  let base
  if (Array.isArray(node.enum) && node.enum.length > 0) {
    base = node.enum.map((value) => JSON.stringify(value)).join(' | ')
  } else if (node.type === 'array') {
    const inner = renderType(node.items, depth)
    base = inner.includes(' ') ? `Array<${inner}>` : `${inner}[]`
  } else if (node.type === 'object' || node.properties) {
    base = renderObject(node, depth)
  } else if (node.type === 'integer' || node.type === 'number') {
    base = 'number'
  } else if (node.type === 'boolean') {
    base = 'boolean'
  } else if (node.type === 'string') {
    base = 'string'
  } else {
    base = 'unknown'
  }
  if (node.nullable === true) base = base.includes(' | ') ? `(${base}) | null` : `${base} | null`
  return base
}

function renderObject(node, depth) {
  const properties = node.properties ?? {}
  const names = Object.keys(properties)
  if (names.length === 0) {
    if (node.additionalProperties === true) return 'Record<string, unknown>'
    if (node.additionalProperties) return `Record<string, ${renderType(node.additionalProperties, depth)}>`
    return 'Record<string, unknown>'
  }
  const required = new Set(Array.isArray(node.required) ? node.required : [])
  const lines = names.map((name) => {
    const optional = required.has(name) ? '' : '?'
    return `${indentOf(depth + 1)}${key(name)}${optional}: ${renderType(properties[name], depth + 1)}`
  })
  return `{\n${lines.join('\n')}\n${indentOf(depth)}}`
}

function collectParameters(spec, item, operation) {
  const merged = []
  for (const raw of [...(item.parameters ?? []), ...(operation.parameters ?? [])]) {
    merged.push(raw.$ref ? deref(spec, raw) : raw)
  }
  const byLocation = { path: {}, query: {}, header: {} }
  for (const parameter of merged) {
    const location = parameter.in
    if (!(location in byLocation)) continue
    const optional = parameter.required === true ? '' : '?'
    byLocation[location][parameter.name] = {
      type: renderType(parameter.schema, 3),
      optional
    }
  }
  return byLocation
}

function deref(spec, node) {
  if (!node || typeof node.$ref !== 'string') return node
  let current = spec
  for (const part of node.$ref.replace(/^#\//, '').split('/')) current = current?.[part]
  return current ?? node
}

function renderParams(entries, depth) {
  const names = Object.keys(entries)
  if (names.length === 0) return 'Record<string, never>'
  const lines = names.map((name) => {
    const { type, optional } = entries[name]
    return `${indentOf(depth + 1)}${key(name)}${optional}: ${type}`
  })
  return `{\n${lines.join('\n')}\n${indentOf(depth)}}`
}

function pickSuccessResponse(spec, operation) {
  const responses = operation.responses ?? {}
  const codes = Object.keys(responses)
  const preferred = codes.includes('200') ? '200' : codes.filter((code) => code.startsWith('2')).sort()[0]
  if (!preferred) return 'unknown'
  const response = deref(spec, responses[preferred])
  const content = response?.content ?? {}
  const mediaType = Object.keys(content)[0]
  if (!mediaType) return 'unknown'
  return renderType(content[mediaType].schema, 3)
}

function pickRequestBody(spec, operation) {
  const body = operation.requestBody ? deref(spec, operation.requestBody) : null
  if (!body) return 'never'
  const content = body.content ?? {}
  const mediaType = Object.keys(content).find((type) => type.includes('json')) ?? Object.keys(content)[0]
  if (!mediaType) return 'never'
  return renderType(content[mediaType].schema, 3)
}

function generate(spec) {
  const schemas = spec.components?.schemas ?? {}
  const schemaNames = Object.keys(schemas).sort()

  const schemaLines = schemaNames.map((name) => `${indentOf(1)}${key(name)}: ${renderType(schemas[name], 1)}`)

  const operations = []
  for (const apiPath of Object.keys(spec.paths ?? {}).sort()) {
    const item = spec.paths[apiPath]
    for (const method of HTTP_METHODS) {
      const operation = item[method]
      if (!operation) continue
      const params = collectParameters(spec, item, operation)
      operations.push({
        id: `${method.toUpperCase()} ${apiPath}`,
        path: renderParams(params.path, 3),
        query: renderParams(params.query, 3),
        headers: renderParams(params.header, 3),
        body: pickRequestBody(spec, operation),
        response: pickSuccessResponse(spec, operation)
      })
    }
  }

  const operationBlocks = operations.map((operation) => {
    return [
      `${indentOf(1)}${JSON.stringify(operation.id)}: {`,
      `${indentOf(2)}path: ${operation.path}`,
      `${indentOf(2)}query: ${operation.query}`,
      `${indentOf(2)}headers: ${operation.headers}`,
      `${indentOf(2)}body: ${operation.body}`,
      `${indentOf(2)}response: ${operation.response}`,
      `${indentOf(1)}}`
    ].join('\n')
  })

  return [
    '/**',
    ' * 由 packages/api-client-ts/scripts/generate.mjs 从 docs/openapi/actionloop-api.json 生成，禁止手改。',
    ` * OpenAPI ${spec.openapi ?? 'unknown'}；路径 ${Object.keys(spec.paths ?? {}).length} 个；schema ${schemaNames.length} 个；操作 ${operations.length} 个。`,
    ' */',
    '',
    'export interface Schemas {',
    schemaLines.join('\n'),
    '}',
    '',
    'export type SchemaName = keyof Schemas',
    '',
    'export interface Operations {',
    operationBlocks.join('\n'),
    '}',
    '',
    'export type OperationId = keyof Operations',
    ''
  ].join('\n')
}

async function main() {
  const checkOnly = process.argv.includes('--check')
  const spec = JSON.parse(await readFile(SPEC_PATH, 'utf8'))
  const content = generate(spec)

  if (checkOnly) {
    let current = ''
    try {
      current = await readFile(OUT_PATH, 'utf8')
    } catch {
      current = ''
    }
    if (current !== content) {
      console.error('生成物与契约不一致：请运行 `pnpm --filter @actionloop/api-client-ts generate` 后重新校验。')
      process.exit(1)
    }
    console.log('生成物与 OpenAPI 基线一致。')
    return
  }

  await mkdir(path.dirname(OUT_PATH), { recursive: true })
  await writeFile(OUT_PATH, content, 'utf8')
  console.log(`已生成 ${path.relative(REPO_ROOT, OUT_PATH)}`)
}

main().catch((error) => {
  console.error(error)
  process.exit(1)
})
