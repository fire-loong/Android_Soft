#!/usr/bin/env node
/**
 * 制品清单（指导书 17.5 第 6 条：制品哈希与构建提交对应）。
 *
 * 输出 build/release/release-manifest.json：
 * - git 提交、分支、工作区是否干净（git 不可用时为 null，不伪装成干净）
 * - 可交付制品的 sha256 与字节数；web 以 dist 整棵目录计算聚合哈希
 * - 依赖锁文件哈希；缺失制品单独列出，不伪造哈希
 *
 * 用法：
 *   node scripts/release-manifest.mjs            # 允许缺失，缺失项记录在 missing
 *   node scripts/release-manifest.mjs --require  # 缺失任一制品即退出码 1
 */
import { createHash } from 'node:crypto'
import { execFileSync } from 'node:child_process'
import { createReadStream, existsSync, mkdirSync, readdirSync, statSync, writeFileSync } from 'node:fs'
import { join, relative } from 'node:path'

const requireAll = process.argv.includes('--require')

const git = (...args) => {
  try {
    return execFileSync('git', args, { cwd: process.cwd(), encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] }).trim()
  } catch {
    return null
  }
}

// 以 git 仓库根为基准，允许从任意子目录调用
const root = git('rev-parse', '--show-toplevel') ?? process.cwd()

const ARTIFACTS = [
  { name: 'api', path: 'services/api/target/actionloop-api.jar' },
  { name: 'worker', path: 'services/worker/target/actionloop-worker.jar' },
  { name: 'web-dist', path: 'apps/web/dist', tree: true },
  { name: 'desktop', path: 'apps/desktop/src-tauri/target/release/actionloop-desktop.exe' },
  { name: 'android', path: 'apps/android/app/build/outputs/apk/debug/app-debug.apk' },
  { name: 'lockfile', path: 'pnpm-lock.yaml' },
]

function sha256File(file) {
  return new Promise((resolve, reject) => {
    const hash = createHash('sha256')
    createReadStream(file)
      .on('data', (chunk) => hash.update(chunk))
      .on('end', () => resolve(hash.digest('hex')))
      .on('error', reject)
  })
}

function walk(dir) {
  const files = []
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const full = join(dir, entry.name)
    if (entry.isDirectory()) {
      files.push(...walk(full))
    } else {
      files.push(full)
    }
  }
  return files
}

/** 目录聚合哈希：按相对路径排序，逐个文件哈希后拼入外层哈希。 */
async function hashTree(dir) {
  const files = walk(dir).sort()
  const outer = createHash('sha256')
  let bytes = 0
  for (const file of files) {
    bytes += statSync(file).size
    const rel = relative(dir, file).split('\\').join('/')
    outer.update(`${rel} ${await sha256File(file)}\n`)
  }
  return { sha256: outer.digest('hex'), bytes, files: files.length }
}

const artifacts = []
const missing = []
for (const item of ARTIFACTS) {
  const abs = join(root, item.path)
  if (!existsSync(abs)) {
    missing.push(item.path)
    continue
  }
  if (item.tree) {
    const tree = await hashTree(abs)
    artifacts.push({ name: item.name, path: item.path, tree: true, ...tree, modifiedAt: statSync(abs).mtime.toISOString() })
  } else {
    const stat = statSync(abs)
    artifacts.push({
      name: item.name,
      path: item.path,
      bytes: stat.size,
      sha256: await sha256File(abs),
      modifiedAt: stat.mtime.toISOString(),
    })
  }
}

const manifest = {
  generatedAt: new Date().toISOString(),
  commit: git('rev-parse', 'HEAD'),
  branch: git('rev-parse', '--abbrev-ref', 'HEAD'),
  // git 不可用时为 null，避免把「无法判断」写成干净
  dirty: (() => {
    const status = git('status', '--porcelain')
    return status === null ? null : status.length > 0
  })(),
  node: process.version,
  artifacts,
  missing,
}

const outFile = join(root, 'build', 'release', 'release-manifest.json')
mkdirSync(join(root, 'build', 'release'), { recursive: true })
writeFileSync(outFile, JSON.stringify(manifest, null, 2) + '\n')

console.log(`commit  ${manifest.commit ?? '(非 git 仓库)'}${manifest.dirty ? '（工作区有未提交改动）' : ''}`)
for (const a of artifacts) {
  const detail = a.tree ? `${a.files} files` : `${a.bytes} B`
  console.log(`OK      ${a.name.padEnd(9)} ${a.sha256.slice(0, 16)}…  ${detail}  ${a.path}`)
}
for (const p of missing) {
  console.log(`MISSING ${p}`)
}
console.log(`清单：${relative(root, outFile)}`)

if (requireAll && missing.length > 0) {
  console.error(`\n缺少 ${missing.length} 个制品，按 --require 失败退出`)
  process.exit(1)
}
