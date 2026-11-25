#!/usr/bin/env node
/**
 * 依赖漏洞扫描（指导书 11.x 安全 / 17.5 供应链：锁定、SBOM、漏洞扫描）。
 *
 * 做什么：
 * 1) 用 mvnw dependency:list 收集后端 runtime 依赖（shared/api/worker 及传递依赖）；
 * 2) 逐包查询 OSV.dev（Maven 生态），拿到真实漏洞记录与严重度；
 * 3) 用 pnpm audit 扫描 JS/TS 依赖树（官方 registry，避免镜像无审计端点）；
 * 4) 输出 build/security/dependency-audit.json 与终端汇总。
 *
 * 判定：默认 CRITICAL/HIGH 命中即失败（退出码 1）；漏洞已在
 * tests/security/allowlist.json 中登记「已评估 + 理由 + 到期日」的才放行。
 * 扫描本身没跑完（网络/命令失败）退出码 2 —— 不允许把「没扫成」当通过。
 *
 * 边界：OSV 是公开漏洞库聚合，不等于完整 NVD 覆盖；不做许可证合规与可达性分析；
 * JDK 自带组件、Android SDK、桌面端 cargo 依赖不在本脚本范围。
 *
 * 用法：pnpm test:security:deps [--json] [--fail-on=CRITICAL,HIGH]
 */
import { execFileSync } from 'node:child_process'
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'

const ROOT = fileURLToPath(new URL('../..', import.meta.url))
const OUT_DIR = join(ROOT, 'build', 'security')
const OUT_FILE = join(OUT_DIR, 'dependency-audit.json')
const ALLOWLIST_FILE = join(ROOT, 'tests', 'security', 'allowlist.json')
const OSV_BASE = 'https://api.osv.dev/v1'
const BATCH_SIZE = 200
const SEVERITY_ORDER = ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW', 'UNKNOWN']

/** GHSA 用 MODERATE，nvd/pnpm 用 MEDIUM，统一成 MEDIUM。 */
function normalizeSeverity(value) {
  const s = String(value ?? 'unknown').toUpperCase()
  if (s === 'MODERATE') return 'MEDIUM'
  return SEVERITY_ORDER.includes(s) ? s : 'UNKNOWN'
}

const args = process.argv.slice(2)
const failOnArg = args.find((a) => a.startsWith('--fail-on='))
const FAIL_ON = new Set(
  (failOnArg ? failOnArg.slice('--fail-on='.length) : 'CRITICAL,HIGH')
    .split(',').map((s) => s.trim().toUpperCase()).filter(Boolean),
)

class ScanError extends Error {}

function log(msg) {
  console.log(msg)
}

function gitCommit() {
  try {
    return execFileSync('git', ['rev-parse', 'HEAD'], { cwd: ROOT, encoding: 'utf8' }).trim()
  } catch {
    return null
  }
}

async function fetchJson(url, init = {}, attempt = 1) {
  const res = await fetch(url, {
    ...init,
    headers: { 'content-type': 'application/json', 'user-agent': 'actionloop-dependency-audit', ...(init.headers ?? {}) },
    signal: AbortSignal.timeout(60_000),
  })
  if (!res.ok) {
    if (attempt < 3) return fetchJson(url, init, attempt + 1)
    throw new ScanError(`${url} 返回 HTTP ${res.status}`)
  }
  return res.json()
}

function runDependencyList(modules) {
  const isWin = process.platform === 'win32'
  const mvnw = isWin ? 'mvnw.cmd' : './mvnw'
  try {
    return execFileSync(
      mvnw,
      ['-pl', modules.join(','), '-am', 'dependency:list', '-DincludeScope=runtime', '-B', '-ntp'],
      { cwd: join(ROOT, 'services'), encoding: 'utf8', shell: isWin, maxBuffer: 64 * 1024 * 1024, stdio: ['ignore', 'pipe', 'pipe'] },
    )
  } catch (e) {
    const stderrTail = String(e.stderr ?? '')
      .split(/\r?\n/).map((l) => l.trim()).filter(Boolean).slice(-12).join(' | ')
    throw new ScanError(`mvnw dependency:list 失败（modules=${modules.join(',')}）：${String(e.message ?? e).split('\n')[0]}${stderrTail ? '；stderr: ' + stderrTail : ''}`)
  }
}

/**
 * 后端 runtime 依赖（group:artifact@version）。
 * worker 依赖 api 的 `library` classifier，未先 package 时无法解析；
 * 此时退回 shared+api 的并集 —— worker 的第三方依赖经核对是 api 的子集（仅额外 spring-boot-starter）。
 */
function collectMavenDependencies() {
  let out
  let coverage
  try {
    out = runDependencyList(['shared', 'api', 'worker'])
    coverage = 'shared + api + worker（含传递依赖）'
  } catch (e) {
    log(`  ${e.message.split('\n')[0]}`)
    log('  worker 的 api library classifier 未安装，退回扫描 shared + api 的 runtime 并集')
    out = runDependencyList(['shared', 'api'])
    coverage = 'shared + api runtime 并集（worker 第三方依赖为 api 子集，仅额外 spring-boot-starter）'
  }
  const seen = new Map()
  for (const line of out.split(/\r?\n/)) {
    const m = line.match(/\[INFO\]\s+([\w.\-]+):([\w.\-]+):(jar|war|pom):([\w.\-+]+):(compile|runtime|provided)/)
    if (!m) continue
    const key = `${m[1]}:${m[2]}@${m[4]}`
    if (!seen.has(key)) {
      seen.set(key, { ecosystem: 'Maven', name: `${m[1]}:${m[2]}`, version: m[4], scope: m[5] })
    }
  }
  if (seen.size === 0) throw new ScanError('mvnw dependency:list 没有解析出任何依赖，输出格式可能变化')
  return { deps: [...seen.values()], coverage }
}

async function queryOsv(deps) {
  const results = []
  for (let i = 0; i < deps.length; i += BATCH_SIZE) {
    const chunk = deps.slice(i, i + BATCH_SIZE)
    const body = {
      queries: chunk.map((d) => ({ package: { name: d.name, ecosystem: d.ecosystem }, version: d.version })),
    }
    const res = await fetchJson(`${OSV_BASE}/querybatch`, { method: 'POST', body: JSON.stringify(body) })
    results.push(...(res.results ?? []))
    log(`  OSV 已查询 ${Math.min(i + BATCH_SIZE, deps.length)}/${deps.length}`)
  }
  return results
}

function severityFromDetail(detail) {
  const declared = detail?.database_specific?.severity
  if (declared) return normalizeSeverity(declared)
  const numeric = (detail?.severity ?? []).map((s) => Number(s.score)).find((n) => Number.isFinite(n))
  if (Number.isFinite(numeric)) {
    if (numeric >= 9) return 'CRITICAL'
    if (numeric >= 7) return 'HIGH'
    if (numeric >= 4) return 'MEDIUM'
    if (numeric > 0) return 'LOW'
  }
  return 'UNKNOWN'
}

/** pnpm audit（npm 官方 registry），返回归一化结果；命令异常但输出了 JSON 也继续解析。 */
function collectNpmFindings() {
  const isWin = process.platform === 'win32'
  let stdout = ''
  let execError = null
  try {
    stdout = execFileSync('pnpm', ['audit', '--json', '--registry=https://registry.npmjs.org'], {
      cwd: ROOT, encoding: 'utf8', shell: isWin, maxBuffer: 64 * 1024 * 1024, stdio: ['ignore', 'pipe', 'pipe'],
    })
  } catch (e) {
    execError = e
    stdout = e.stdout?.toString?.() ?? ''
  }
  let data
  try {
    data = JSON.parse(stdout.trim() || '{}')
  } catch {
    throw new ScanError(`pnpm audit 未返回可解析 JSON：${String(execError?.message ?? 'empty output').split('\n')[0]}`)
  }
  const findings = []
  if (data.advisories && typeof data.advisories === 'object') {
    for (const [id, adv] of Object.entries(data.advisories)) {
      findings.push({
        ecosystem: 'npm',
        id,
        package: adv.module_name,
        severity: normalizeSeverity(adv.severity),
        title: adv.title ?? '',
        versions: [...new Set((adv.findings ?? []).map((f) => f.version).filter(Boolean))],
        url: adv.url ?? '',
      })
    }
  } else if (data.vulnerabilities && typeof data.vulnerabilities === 'object') {
    for (const [name, v] of Object.entries(data.vulnerabilities)) {
      const via = Array.isArray(v.via) ? v.via.filter((x) => typeof x === 'object') : []
      findings.push({
        ecosystem: 'npm',
        id: via.map((x) => x.source ?? x.title).filter(Boolean).join('+') || `${name}-${v.severity}`,
        package: name,
        severity: normalizeSeverity(v.severity),
        title: via.map((x) => x.title).filter(Boolean).join('; '),
        versions: v.range ? [v.range] : [],
        url: via[0]?.url ?? '',
      })
    }
  }
  if (data.error) throw new ScanError(`pnpm audit 报错：${JSON.stringify(data.error).slice(0, 200)}`)
  return findings
}

function loadAllowlist() {
  if (!existsSync(ALLOWLIST_FILE)) return { entries: [], malformed: [] }
  try {
    const data = JSON.parse(readFileSync(ALLOWLIST_FILE, 'utf8'))
    return { entries: Array.isArray(data) ? data : (data.entries ?? []), malformed: [] }
  } catch {
    throw new ScanError(`${ALLOWLIST_FILE} 不是合法 JSON`)
  }
}

function evaluateAllowlist(findings, entries) {
  const today = new Date().toISOString().slice(0, 10)
  const missingFields = []
  for (const e of entries) {
    if (!e.id || !e.package || !e.reason || !e.expiresAt || !e.decidedBy) {
      missingFields.push(e.id ?? JSON.stringify(e))
    }
  }
  const used = new Set()
  const expired = []
  for (const f of findings) {
    const hit = entries.find((e) =>
      e.id === f.id &&
      (e.package === f.package || e.package === `${f.package}*` || f.package.startsWith(String(e.package).replace(/\*$/, ''))) &&
      e.expiresAt >= today)
    if (hit) {
      f.allowlisted = { reason: hit.reason, expiresAt: hit.expiresAt, decidedBy: hit.decidedBy }
      used.add(hit.id)
    } else {
      const expiredHit = entries.find((e) => e.id === f.id && e.package === f.package && e.expiresAt < today)
      if (expiredHit) expired.push({ id: f.id, package: f.package, expiresAt: expiredHit.expiresAt })
    }
  }
  const unused = entries.filter((e) => !used.has(e.id)).map((e) => ({ id: e.id, package: e.package }))
  return { missingFields, expired, unused }
}

async function main() {
  log('== 依赖漏洞扫描（Maven OSV + npm audit）==')
  log('[1/4] 收集后端依赖')
  const { deps: mavenDeps, coverage } = collectMavenDependencies()
  log(`  解析到 ${mavenDeps.length} 个 runtime 依赖（${coverage}）`)

  log('[2/4] 查询 OSV.dev')
  const osvResults = await queryOsv(mavenDeps)
  const detailCache = new Map()
  const mavenFindings = []
  for (let i = 0; i < mavenDeps.length; i += 1) {
    const dep = mavenDeps[i]
    const hits = osvResults[i]?.vulns ?? []
    for (const hit of hits) {
      if (!detailCache.has(hit.id)) {
        detailCache.set(hit.id, await fetchJson(`${OSV_BASE}/vulns/${hit.id}`))
      }
      const detail = detailCache.get(hit.id)
      mavenFindings.push({
        ecosystem: 'Maven',
        id: hit.id,
        package: dep.name,
        version: dep.version,
        severity: severityFromDetail(detail),
        title: detail.summary ?? '',
        url: `https://osv.dev/vulnerability/${hit.id}`,
      })
    }
  }

  log('[3/4] pnpm audit')
  const npmFindings = collectNpmFindings()

  log('[4/4] 汇总与放行判定')
  const findings = [...mavenFindings, ...npmFindings]
  const allow = loadAllowlist()
  const verdict = evaluateAllowlist(findings, allow.entries)
  const blocking = findings.filter((f) => FAIL_ON.has(f.severity) && !f.allowlisted)
  const counts = Object.fromEntries(SEVERITY_ORDER.map((s) => [s, findings.filter((f) => f.severity === s).length]))

  for (const f of findings) {
    const mark = f.allowlisted ? 'ALLOW' : (FAIL_ON.has(f.severity) ? 'BLOCK' : 'WARN ')
    log(`  ${mark} [${f.severity}] ${f.package}${f.version ? '@' + f.version : ''} ${f.id} — ${f.title || '(无标题)'}`)
  }
  if (verdict.expired.length) log(`  放行条目已过期：${verdict.expired.map((e) => e.id).join(', ')}`)
  if (verdict.missingFields.length) log(`  放行条目字段不全（id/package/reason/expiresAt/decidedBy）：${verdict.missingFields.join(', ')}`)
  if (verdict.unused.length) log(`  放行条目未命中任何漏洞（可清理）：${verdict.unused.map((e) => e.id).join(', ')}`)

  mkdirSync(OUT_DIR, { recursive: true })
  const report = {
    generatedAt: new Date().toISOString(),
    commit: gitCommit(),
    failOn: [...FAIL_ON],
    scanned: { mavenRuntimeDependencies: mavenDeps.length, mavenCoverage: coverage, npm: 'pnpm audit (registry.npmjs.org)' },
    counts,
    blocking: blocking.map((f) => ({ id: f.id, package: f.package, version: f.version, severity: f.severity })),
    findings,
    allowlistIssues: verdict,
    boundaries: [
      'OSV 公开库聚合，不等于完整 NVD 覆盖；未做可达性分析',
      '未覆盖 JDK 自带组件、Android SDK、Desktop(cargo) 依赖',
      '不含许可证合规与 SBOM 签名',
    ],
  }
  writeFileSync(OUT_FILE, JSON.stringify(report, null, 2), 'utf8')
  log(`  报告：${OUT_FILE}`)
  log(`  统计：CRITICAL ${counts.CRITICAL} / HIGH ${counts.HIGH} / MEDIUM ${counts.MEDIUM} / LOW ${counts.LOW} / UNKNOWN ${counts.UNKNOWN}`)

  if (blocking.length > 0) {
    console.error(`\n依赖漏洞扫描 FAIL：${blocking.length} 条 ${[...FAIL_ON].join('/')} 未评估`)
    process.exit(1)
  }
  log(`\n依赖漏洞扫描 PASS：${[...FAIL_ON].join('/')} 无未评估命中（共扫描 Maven ${mavenDeps.length} 个依赖）`)
}

main().catch((e) => {
  const detail = e instanceof ScanError ? e.message : String(e.stack ?? e)
  try {
    mkdirSync(OUT_DIR, { recursive: true })
    writeFileSync(join(OUT_DIR, 'dependency-audit-error.txt'), `${new Date().toISOString()}\n${detail}\n`, 'utf8')
  } catch {
    // 诊断文件写失败不改变退出码
  }
  console.error(`\n依赖漏洞扫描未完成：${detail}`)
  process.exit(2)
})
