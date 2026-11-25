#!/usr/bin/env node
/**
 * 生成后端 CycloneDX SBOM（指导书 17.5 供应链：SBOM + 依赖锁定 + 漏洞扫描）。
 *
 * 做法：调用 services 的 cyclonedx-maven-plugin `makeAggregateBom`（插件自带 schema 校验），
 * 再对产物做结构自检（bomFormat/specVersion/组件数量/组件 name+version 完整率），
 * 复制到 build/sbom/maven-sbom.json 作为发布证据。
 *
 * 边界：只覆盖 Maven 后端（shared/api/worker）；不含 Web/Android/Desktop 依赖，
 * 也不含许可证合规判定与签名。
 *
 * 用法：pnpm release:sbom
 */
import { execFileSync } from 'node:child_process'
import { copyFileSync, existsSync, mkdirSync, readFileSync } from 'node:fs'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'

const ROOT = fileURLToPath(new URL('..', import.meta.url))
const SERVICES = join(ROOT, 'services')
const SOURCE = join(SERVICES, 'target', 'actionloop-sbom.json')
const OUT_DIR = join(ROOT, 'build', 'sbom')
const OUT_FILE = join(OUT_DIR, 'maven-sbom.json')
const MIN_COMPONENTS = 50

function fail(message) {
  console.error(`SBOM 生成失败：${message}`)
  process.exit(1)
}

const isWin = process.platform === 'win32'
try {
  execFileSync(
    isWin ? 'mvnw.cmd' : './mvnw',
    ['cyclonedx:makeAggregateBom', '-DoutputFormat=json', '-DoutputName=actionloop-sbom', '-B', '-ntp'],
    { cwd: SERVICES, encoding: 'utf8', shell: isWin, stdio: ['ignore', 'pipe', 'inherit'] },
  )
} catch (e) {
  fail(`mvnw cyclonedx:makeAggregateBom 出错：${String(e.message ?? e).split('\n')[0]}`)
}

if (!existsSync(SOURCE)) fail(`未生成 ${SOURCE}`)

let bom
try {
  bom = JSON.parse(readFileSync(SOURCE, 'utf8'))
} catch {
  fail('产物不是合法 JSON')
}

if (bom.bomFormat !== 'CycloneDX') fail(`bomFormat 不是 CycloneDX：${bom.bomFormat}`)
if (!bom.specVersion) fail('缺少 specVersion')
const components = Array.isArray(bom.components) ? bom.components : []
if (components.length < MIN_COMPONENTS) fail(`组件数 ${components.length} < ${MIN_COMPONENTS}，疑似聚合失败`)
const incomplete = components.filter((c) => !c.name || !c.version)
if (incomplete.length > 0) fail(`${incomplete.length} 个组件缺少 name/version，例如 ${JSON.stringify(incomplete[0])}`)

const unique = new Set(components.map((c) => `${c.group ?? ''}:${c.name}@${c.version}`))

mkdirSync(OUT_DIR, { recursive: true })
copyFileSync(SOURCE, OUT_FILE)

console.log('== 后端 SBOM（CycloneDX）==')
console.log(`  规范版本：${bom.specVersion}，组件：${components.length}（去重 ${unique.size}）`)
console.log(`  序列号：${bom.serialNumber ?? '(无)'}`)
console.log(`  输出：${OUT_FILE}`)
console.log('  边界：仅 Maven 后端依赖，不含 Web/Android/Desktop 与许可证合规判定')
