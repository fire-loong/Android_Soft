import { randomUUID } from 'node:crypto'
import { expect, test, type APIRequestContext, type Page } from '@playwright/test'

const API_BASE = 'http://127.0.0.1:8080/api/v1'

interface ApiEnvelope<T> {
  data: T
}

interface TestUser {
  token: string
  workspaceId: string
}

interface SharedSource {
  id: string
  candidateIds: string[]
  evidence: unknown[]
}

function authHeaders(token: string) {
  return { Authorization: `Bearer ${token}` }
}

function runId(): string {
  return randomUUID().replaceAll('-', '').slice(0, 12)
}

function shanghaiParts(date: Date): Record<string, string> {
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Asia/Shanghai',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hourCycle: 'h23'
  }).formatToParts(date)
  return Object.fromEntries(parts.filter((part) => part.type !== 'literal').map((part) => [part.type, part.value]))
}

function futureDate(days: number): Date {
  const date = new Date(Date.now() + days * 24 * 60 * 60 * 1000)
  date.setMinutes(0, 0, 0)
  return date
}

function chineseDateTime(date: Date): string {
  const p = shanghaiParts(date)
  return `${Number(p.year)}年${Number(p.month)}月${Number(p.day)}日${p.hour}:${p.minute}`
}

function pickerDateTime(date: Date): string {
  const p = shanghaiParts(date)
  return `${p.year}-${p.month}-${p.day} ${p.hour}:${p.minute}`
}

async function registerAndGetPersonalWorkspace(
  page: Page,
  request: APIRequestContext,
  id: string
): Promise<TestUser> {
  const username = `e2e${id}`
  await page.goto('/login')
  await page.getByText('注册', { exact: true }).click()
  await page.getByLabel('邮箱', { exact: true }).fill(`${username}@e2e.test`)
  await page.getByLabel('用户名', { exact: true }).fill(username)
  await page.getByLabel('昵称', { exact: true }).fill(`E2E ${id}`)
  await page.getByLabel('密码', { exact: true }).fill('Passw0rd!')
  await page.getByRole('button', { name: '注册并登录' }).click()
  await expect(page).toHaveURL(/\/today$/)

  const token = await page.evaluate(() => localStorage.getItem('actionloop.accessToken'))
  expect(token).toBeTruthy()

  const meResponse = await request.get(`${API_BASE}/me`, { headers: authHeaders(token!) })
  expect(meResponse.status()).toBe(200)
  const me = (await meResponse.json()) as ApiEnvelope<{
    workspaces: Array<{ id: string; type: 'PERSONAL' | 'TEAM' }>
  }>
  const personal = me.data.workspaces.find((workspace) => workspace.type === 'PERSONAL')
  expect(personal).toBeDefined()
  return { token: token!, workspaceId: personal!.id }
}

async function shareRuleSource(
  request: APIRequestContext,
  user: TestUser,
  id: string,
  title: string,
  text: string
): Promise<SharedSource> {
  const response = await request.post(`${API_BASE}/sources/share`, {
    headers: authHeaders(user.token),
    data: {
      workspaceId: user.workspaceId,
      sourceType: 'NOTIFICATION',
      sourceApp: 'com.actionloop.e2e',
      sourceKey: `e2e-${id}`,
      title,
      text,
      timezone: 'Asia/Shanghai',
      localOnly: true
    }
  })
  expect(response.status()).toBe(200)
  const source = ((await response.json()) as ApiEnvelope<SharedSource>).data
  expect(source.candidateIds.length).toBeGreaterThan(0)
  expect(source.evidence.length).toBeGreaterThan(0)
  return source
}

async function openConfirmation(page: Page, keyword: string) {
  await page.goto('/inbox')
  const candidate = page.locator('.ant-card').filter({ hasText: keyword }).first()
  await expect(candidate).toBeVisible()
  await candidate.getByRole('button', { name: '确认并创建事项' }).click()
  const modal = page.locator('.ant-modal-content').filter({ hasText: '确认候选并创建事项' })
  await expect(modal).toBeVisible()
  return { candidate, modal }
}

async function confirmAndOpenTask(page: Page) {
  const success = page.locator('.ant-modal-content').filter({ hasText: '事项已创建' })
  await expect(success).toBeVisible()
  await success.getByRole('button', { name: '查看事项' }).click()
  await expect(page).toHaveURL(/\/tasks\/[0-9a-f-]+$/)
  return page.url().split('/').pop()!
}

test('来源经用户确认后完成事项、凭证核验并沉淀个人记忆', async ({ page, request }) => {
  const id = runId()
  const keyword = `E2E记忆${id}`
  const taskTitle = `E2E主链路事项${id}`
  const step1 = `准备${id}材料`
  const step2 = `上传${id}回执`
  const completionStatement = `已完成并留存回执${id}`
  const sourceText = `请于${chineseDateTime(futureDate(14))}前在课程平台提交${keyword}课程设计PDF，需要携带学生证和成绩单，地点：教务处`

  const user = await registerAndGetPersonalWorkspace(page, request, id)
  await shareRuleSource(request, user, id, `${keyword}通知`, sourceText)

  await page.goto('/inbox')
  const candidate = page.locator('.ant-card').filter({ hasText: keyword }).first()
  await expect(candidate).toBeVisible()
  await expect(candidate).toContainText('规则')
  await candidate.getByRole('button', { name: '查看原文证据' }).click()
  const evidenceDrawer = page.locator('.ant-drawer-content').filter({ hasText: '原文来源' })
  await expect(evidenceDrawer).toBeVisible()
  await expect(evidenceDrawer).toContainText(keyword)
  await expect(evidenceDrawer).toContainText('证据引用')
  await evidenceDrawer.locator('.ant-drawer-close').click()

  const { modal } = await openConfirmation(page, keyword)
  await expect(modal).toContainText('规则')
  await modal.getByRole('switch').click()
  await expect(modal.getByRole('switch')).toBeChecked()
  await modal.getByLabel('步骤（每行一个，留空则由规则生成）', { exact: true }).fill(`${step1}\n${step2}`)
  const materials = modal.getByLabel('所需材料', { exact: true })
  await materials.fill('学生证')
  await materials.press('Enter')
  await materials.fill('成绩单')
  await materials.press('Enter')
  await materials.press('Escape')
  const reminder = modal.getByLabel('提前提醒', { exact: true })
  await reminder.focus()
  await reminder.press('ArrowDown')
  await page.locator('.ant-select-dropdown:visible').getByText('提前 1 小时', { exact: true }).click()
  const titleInput = modal.getByLabel('事项标题', { exact: true })
  await titleInput.fill(taskTitle)
  await expect(titleInput).toHaveValue(taskTitle)
  await modal.getByRole('button', { name: '确认创建' }).click()
  await confirmAndOpenTask(page)

  await expect(page.getByRole('heading', { name: taskTitle })).toBeVisible()
  await expect(page.getByText(step1, { exact: true })).toBeVisible()
  await expect(page.getByText(step2, { exact: true })).toBeVisible()
  await expect(page.locator('.ant-card').filter({ hasText: '材料清单' })).toContainText('学生证')
  await expect(page.locator('.ant-card').filter({ hasText: '材料清单' })).toContainText('成绩单')
  await expect(page.locator('.ant-card').filter({ hasText: '提醒计划' })).toContainText('NOTIFICATION')
  await expect(page.locator('.ant-card').filter({ hasText: '来源证据' })).toContainText(keyword)

  await page.getByText(step1, { exact: true }).click()
  await page.getByLabel('提交完成说明', { exact: true }).fill(completionStatement)
  await page.getByRole('button', { name: '提交完成凭证' }).click()
  const completionCard = page.locator('.ant-card-type-inner').filter({ hasText: completionStatement })
  await expect(completionCard).toBeVisible()
  await expect(completionCard).toContainText('待复核')
  await completionCard.getByRole('button', { name: '通过核验' }).click()
  await expect(completionCard).toContainText('已通过')
  await expect(page.getByText('已完成', { exact: true }).first()).toBeVisible()

  await page.goto('/memory')
  await page.getByPlaceholder('输入关键词，例如：助学金、成绩单、宿舍电费').fill(keyword)
  await page.getByRole('button', { name: /搜\s*索/ }).click()
  const hit = page.locator('.ant-card').filter({ hasText: keyword }).first()
  await expect(hit).toBeVisible()
  await hit.click()
  const context = page.locator('.ant-drawer-content').filter({ hasText: '情境重现' })
  await expect(context).toBeVisible()
  await expect(context).toContainText(keyword)
  await expect(context).toContainText('内容定位')
  await expect(context).toContainText(taskTitle)
})

test('多日期低置信候选未核对时拒绝创建，显式核对后成功且无默认提醒', async ({ page, request }) => {
  const id = runId()
  const keyword = `E2E低置信${id}`
  const sourceText = `11月25日上午9点提交${keyword}报名表，11月27日下午3点提交缴费单`
  const user = await registerAndGetPersonalWorkspace(page, request, id)
  await shareRuleSource(request, user, id, `${keyword}通知`, sourceText)

  const { candidate, modal } = await openConfirmation(page, keyword)
  await expect(candidate).toContainText('多个日期')
  const due = futureDate(10)
  const dueInput = modal.getByLabel('截止时间（留空会沿用候选时间）', { exact: true })
  await dueInput.fill(pickerDateTime(due))
  await page.locator('.ant-picker-dropdown:visible').getByRole('button', { name: /确\s*定/ }).click()
  await expect(modal.getByRole('switch')).not.toBeChecked()
  await modal.getByRole('button', { name: '确认创建' }).click()
  await expect(page.locator('.ant-message')).toContainText(/日期|截止|核对/)
  await expect(modal).toBeVisible()

  const rejectedTasksResponse = await request.get(`${API_BASE}/tasks?workspaceId=${user.workspaceId}`, {
    headers: authHeaders(user.token)
  })
  expect(rejectedTasksResponse.status()).toBe(200)
  const rejectedTasks = (await rejectedTasksResponse.json()) as ApiEnvelope<{ content: unknown[] }>
  expect(rejectedTasks.data.content).toHaveLength(0)

  await modal.getByRole('switch').click()
  await expect(modal.getByRole('switch')).toBeChecked()
  await modal.getByRole('button', { name: '确认创建' }).click()
  const taskId = await confirmAndOpenTask(page)
  await expect(page.locator('.ant-descriptions')).toContainText('截止')

  const taskResponse = await request.get(`${API_BASE}/tasks/${taskId}?workspaceId=${user.workspaceId}`, {
    headers: authHeaders(user.token)
  })
  expect(taskResponse.status()).toBe(200)
  const task = (await taskResponse.json()) as ApiEnvelope<{ dueAt: string | null }>
  expect(task.data.dueAt).toBeTruthy()
  expect(Date.parse(task.data.dueAt!)).toBe(due.getTime())

  const remindersResponse = await request.get(
    `${API_BASE}/tasks/${taskId}/reminders?workspaceId=${user.workspaceId}`,
    { headers: authHeaders(user.token) }
  )
  expect(remindersResponse.status()).toBe(200)
  const reminders = (await remindersResponse.json()) as ApiEnvelope<unknown[]>
  expect(reminders.data).toHaveLength(0)
})

test('账户注销需密码确认进入冷却期，取消后恢复可用', async ({ page, request }) => {
  const id = runId()
  const user = await registerAndGetPersonalWorkspace(page, request, id)

  await page.goto('/audit')
  const card = page.locator('.ant-card').filter({ hasText: '账户注销' }).first()
  await expect(card).toContainText('未申请')
  await card.getByRole('button', { name: '申请注销账户' }).click()

  const modal = page.locator('.ant-modal-content').filter({ hasText: '申请注销账户' })
  await expect(modal).toBeVisible()
  await modal.getByPlaceholder('当前密码').fill('Passw0rd!')
  await modal.getByRole('button', { name: '确认申请注销' }).click()

  await expect(page.locator('.ant-message')).toContainText('冷却期')
  await expect(card).toContainText('冷却中')
  await expect(card).toContainText('计划清除时间')

  const pendingResponse = await request.get(`${API_BASE}/me`, { headers: authHeaders(user.token) })
  expect(pendingResponse.status()).toBe(200)
  const pending = (await pendingResponse.json()) as ApiEnvelope<{
    accountDeletion: { state: string; scheduledAt?: string }
  }>
  expect(pending.data.accountDeletion.state).toBe('PENDING')
  expect(pending.data.accountDeletion.scheduledAt).toBeTruthy()

  await card.getByRole('button', { name: '取消注销' }).click()
  await expect(page.locator('.ant-message')).toContainText('已取消')
  await expect(card).toContainText('未申请')

  const cancelledResponse = await request.get(`${API_BASE}/me`, { headers: authHeaders(user.token) })
  const cancelled = (await cancelledResponse.json()) as ApiEnvelope<{
    accountDeletion: { state: string }
  }>
  expect(cancelled.data.accountDeletion.state).toBe('NONE')
})

test('空间导出需密码二次认证，创建为可追踪的异步任务', async ({ page, request }) => {
  const id = runId()
  const user = await registerAndGetPersonalWorkspace(page, request, id)

  await page.goto('/workspaces')
  await page.getByRole('tab', { name: '数据与导出' }).click()
  await page.getByRole('button', { name: '创建空间导出' }).click()

  const modal = page.locator('.ant-modal-content').filter({ hasText: '创建空间导出' })
  await expect(modal).toBeVisible()
  await modal.getByLabel('当前密码', { exact: true }).fill('WrongPass1!')
  const okButton = modal.getByRole('button', { name: '确认导出' })
  await okButton.click()
  await expect(page.locator('.ant-message')).toContainText('密码错误')
  // 密码错误会触发一次 401→refresh→重试，等按钮退出 loading 再提交，避免点击被 antd 忽略。
  await expect(okButton).not.toHaveClass(/ant-btn-loading/)

  await modal.getByLabel('当前密码', { exact: true }).fill('Passw0rd!')
  await okButton.click()
  await expect(page.locator('.ant-message')).toContainText('导出任务已创建')
  await expect(modal).toBeHidden()

  const table = page.locator('.ant-card').filter({ hasText: '数据与导出' })
  await expect(table.locator('.ant-table-row').first()).toBeVisible()

  const exportsResponse = await request.get(`${API_BASE}/exports`, { headers: authHeaders(user.token) })
  expect(exportsResponse.status()).toBe(200)
  const exports = (await exportsResponse.json()) as ApiEnvelope<Array<{ id: string; state: string }>>
  expect(exports.data.length).toBeGreaterThan(0)
  // E2E 只启动 API，worker 可能未运行；任务至少应处于 PENDING，若 worker 在线则可先变 READY。
  expect(['PENDING', 'READY']).toContain(exports.data[0].state)
})
