#!/usr/bin/env node
/**
 * X-03 备份恢复演练（指导书 17.3 / 17.5 第 4 条）。
 *
 * 做什么：
 * 1) 用 pg_dump -Fc 备份当前库；
 * 2) 在同一个 PostgreSQL 实例上新建空库 actionloop_restore_check；
 * 3) pg_restore 全量恢复；
 * 4) 逐表比对行数，并对小表比对整表内容 md5；
 * 5) 删除演练库与临时备份文件，输出 PASS/FAIL。
 *
 * 边界（必须如实写进报告）：这是「同实例、新数据库」恢复演练，不等于干净机器 / 异地介质恢复，
 * 也不覆盖备份文件的加密、传输与保留策略。
 *
 * 用法：node tests/ops/backup-restore-drill.mjs
 * 依赖：pg_dump / pg_restore / psql（默认读 PG_BIN，其次 PATH）。
 */
import { execFileSync } from 'node:child_process'
import { existsSync, mkdtempSync, readFileSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

const RESTORE_DB = 'actionloop_restore_check'
const CONTENT_CHECK_LIMIT = 5000

function loadEnvLocal() {
  const path = join(process.cwd(), '.env.local')
  if (!existsSync(path)) return
  for (const raw of readFileSync(path, 'utf8').split(/\r?\n/)) {
    const line = raw.trim()
    if (!line || line.startsWith('#') || !line.includes('=')) continue
    const idx = line.indexOf('=')
    const key = line.slice(0, idx).trim()
    if (!(key in process.env)) process.env[key] = line.slice(idx + 1).trim()
  }
}

function resolvePgBin() {
  const candidates = [
    process.env.PG_BIN,
    'D:\\SoftWare\\PostgreSQL\\17\\bin',
    'C:\\Program Files\\PostgreSQL\\17\\bin',
    'C:\\Program Files\\PostgreSQL\\16\\bin',
  ].filter(Boolean)
  for (const dir of candidates) {
    const dump = join(dir, process.platform === 'win32' ? 'pg_dump.exe' : 'pg_dump')
    if (existsSync(dump)) return dir
  }
  // 退回 PATH
  try {
    execFileSync('pg_dump', ['--version'], { stdio: 'ignore' })
    return ''
  } catch {
    throw new Error('找不到 pg_dump：请设置 PG_BIN 指向 PostgreSQL bin 目录')
  }
}

function tool(bin, name) {
  if (!bin) return name
  return join(bin, process.platform === 'win32' ? `${name}.exe` : name)
}

loadEnvLocal()

const env = {
  ...process.env,
  PGPASSWORD: process.env.ACTIONLOOP_DB_PASSWORD ?? '',
  PGCONNECT_TIMEOUT: '10',
}

function psql(bin, db, sql) {
  return execFileSync(
    tool(bin, 'psql'),
    ['-h', host, '-p', port, '-U', user, '-d', db, '-w', '-t', '-A', '-v', 'ON_ERROR_STOP=1', '-c', sql],
    { env, encoding: 'utf8' },
  ).trim()
}

const host = process.env.ACTIONLOOP_DB_HOST ?? '127.0.0.1'
const port = process.env.ACTIONLOOP_DB_PORT ?? '5433'
const user = process.env.ACTIONLOOP_DB_USER ?? 'postgres'
const sourceDb = process.env.ACTIONLOOP_DB_NAME ?? 'actionloop'
const bin = resolvePgBin()
const workDir = mkdtempSync(join(tmpdir(), 'actionloop-restore-'))
const dumpFile = join(workDir, 'actionloop.dump')

const failures = []
function check(name, ok, detail = '') {
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? ' — ' + detail : ''}`)
  if (!ok) failures.push(name)
}

// 防误删：源库名与演练库名相同（或指向系统库）时直接退出，绝不执行 DROP。
if (sourceDb === RESTORE_DB || ['postgres', 'template0', 'template1'].includes(sourceDb)) {
  console.error(`拒绝执行：源库名 ${sourceDb} 与演练库冲突或为系统库，请检查 ACTIONLOOP_DB_NAME`)
  process.exit(2)
}

try {
  console.log(`[1/5] pg_dump ${sourceDb} → ${dumpFile}`)
  execFileSync(tool(bin, 'pg_dump'), [
    '-h', host, '-p', port, '-U', user, '-w', '-Fc', '-f', dumpFile, sourceDb,
  ], { env, stdio: 'inherit' })
  check('备份文件已生成', existsSync(dumpFile))

  console.log(`[2/5] 重建空库 ${RESTORE_DB}`)
  psql(bin, 'postgres', `DROP DATABASE IF EXISTS ${RESTORE_DB} WITH (FORCE)`)
  psql(bin, 'postgres', `CREATE DATABASE ${RESTORE_DB}`)

  console.log('[3/5] pg_restore 全量恢复')
  execFileSync(tool(bin, 'pg_restore'), [
    '-h', host, '-p', port, '-U', user, '-d', RESTORE_DB, '-w', '--no-owner', '--no-privileges',
    '--exit-on-error', dumpFile,
  ], { env, stdio: 'inherit' })

  console.log('[4/5] 逐表比对行数与内容')
  const listTables = (db) => psql(bin, db,
    "SELECT tablename FROM pg_tables WHERE schemaname='public' ORDER BY tablename")
    .split('\n').map((t) => t.trim()).filter(Boolean)
  const tables = listTables(sourceDb)
  const restoredTables = listTables(RESTORE_DB)
  check('源库存在业务表', tables.length > 0, `${tables.length} 张表`)
  check('恢复库表集合与源库一致',
    restoredTables.length === tables.length && tables.every((t, i) => t === restoredTables[i]),
    `源 ${tables.length} / 恢复 ${restoredTables.length}`)

  let comparedTables = 0
  let comparedRows = 0
  let contentChecked = 0
  for (const table of tables) {
    const srcCount = psql(bin, sourceDb, `SELECT count(*) FROM "${table}"`)
    const dstCount = psql(bin, RESTORE_DB, `SELECT count(*) FROM "${table}"`)
    comparedTables += 1
    comparedRows += Number(srcCount)
    if (srcCount !== dstCount) {
      check(`表 ${table} 行数一致`, false, `源 ${srcCount} / 恢复 ${dstCount}`)
      continue
    }
    if (Number(srcCount) > 0 && Number(srcCount) <= CONTENT_CHECK_LIMIT) {
      const sql = `SELECT coalesce(md5(string_agg(t::text, '|' ORDER BY t::text)), 'empty') FROM "${table}" t`
      const srcHash = psql(bin, sourceDb, sql)
      const dstHash = psql(bin, RESTORE_DB, sql)
      contentChecked += 1
      if (srcHash !== dstHash) {
        check(`表 ${table} 内容一致`, false, `md5 ${srcHash} / ${dstHash}`)
      }
    }
  }
  check('所有表行数一致', failures.filter((f) => f.includes('行数')).length === 0,
    `${comparedTables} 张表 / ${comparedRows} 行`)
  check('抽样表内容 md5 一致', failures.filter((f) => f.includes('内容一致')).length === 0,
    `${contentChecked} 张非空表整表校验`)

  console.log(`[5/5] 清理 ${RESTORE_DB}`)
  psql(bin, 'postgres', `DROP DATABASE IF EXISTS ${RESTORE_DB} WITH (FORCE)`)
  check('演练库已删除',
    psql(bin, 'postgres', `SELECT count(*) FROM pg_database WHERE datname='${RESTORE_DB}'`) === '0')
} catch (e) {
  check('演练过程无异常', false, String(e.message ?? e).split('\n')[0])
} finally {
  rmSync(workDir, { recursive: true, force: true })
}

if (failures.length > 0) {
  console.error(`\nX-03 备份恢复演练 FAIL：${failures.length} 项未通过`)
  process.exit(1)
}
console.log('\nX-03 备份恢复演练 PASS（同实例新库；干净机器/异地介质未覆盖）')
