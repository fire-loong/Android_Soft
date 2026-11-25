#!/usr/bin/env node
/**
 * 制品清单（指导书 17.5 第 6 条：制品哈希、构建提交、依赖锁与测试报告一一对应）。
 *
 * 输出 build/release/release-manifest.json：
 * - git 提交、工作区是否干净、包管理器版本
 * - 每个可交付制品的 sha256 与字节数；缺失的制品单独列出，不伪造哈希
 *
 * 用法：
 *   node scripts/release-manifest.mjs            # 允许缺失，缺失项记录在 missing
 *   node scripts/release-manifest.mjs --require  # 缺失任一制品即退出码 1
 */
import { createHash } from 'node:crypto'
import { execFileSync } from 'node:child_process'
import { existsSync, mkdirSync, readFileSync, statSync, writeFileSync } from 'node:fs'
import { join, relative } from 'node:path'
const root = process.cwd()
const requireAll = process.argv.includes('--require')

const ARTIFACTS = [
  { name: 'api', path: 'services/api/target/actionloop-api.jar' },
  { name: 'worker', path: 'services/worker/target/actionloop-worker.jar' },
  { name: 'web', path: 'apps/web/dist/index.html' },
  { name: 'desktop', path: 'apps/desktop/src-tauri/target/release/actionloop-desktop.exe' },
  { name: 'android', path: 'apps/android/app/build/outputs/apk/debug/app-debug.apk' },
]

const git = (...args) => {
  try {
    return execFileSync('git', args, { cwd: root, encoding: 'utf8' }).trim()
  } catch {
    return null
  }
}

const sha256 = (file) =>
  createHash('sha256').update(readFileSync(file)).digest('hex')

const artifacts = []
const missing = []
for (const item of ARTIFACTS) {
  const abs = join(root, item.path)
  if (!existsSync(abs)) {
    missing.push(item.path)
    continue
  }
  const stat = statSync(abs)
  artifacts.push({
    name: item.name,
    path: item.path,
    bytes: stat.size,
    sha256: sha256(abs),
    modifiedAt: stat.mtime.toISOString(),
  })
}

const packageJson = JSON.parse(readFileSync(join(root, 'package.json'), 'utf8'))
const manifest = {
  generatedAt: new Date().toISOString(),
  commit: git('rev-parse', 'HEAD'),
  branch: git('rev-parse', '--abbrev-ref', 'HEAD'),
  dirty: (git('status', '--porcelain') ?? '').length > 0,
  packageManager: packageJson.packageManager ?? null,
  node: process.version,
  artifacts,
  missing,
}

const outDir = join(root, 'build', 'release')
mkdirSync(outDir, { recursive: true })
const outFile = join(outDir, 'release-manifest.json')
writeFileSync(outFile, JSON.stringify(manifest, null, 2) + '\n')

console.log(`commit  ${manifest.commit ?? '(非 git 仓库)'}${manifest.dirty ? '（工作区有未提交改动）' : ''}`)
for (const a of artifacts) {
  console.log(`OK      ${a.name.padEnd(8)} ${a.sha256.slice(0, 16)}…  ${a.bytes} B  ${a.path}`)
}
for (const p of missing) {
  console.log(`MISSING ${p}`)
}
console.log(`清单：${relative(root, outFile)}`)

if (requireAll && missing.length > 0) {
  console.error(`\n缺少 ${missing.length} 个制品，按 --require 失败退出`)
  process.exit(1)
}
