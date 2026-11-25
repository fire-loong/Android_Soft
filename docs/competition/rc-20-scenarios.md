# RC 固定 20 条端到端场景（指导书 0.6 / 27.5）

> 用途：每次 RC（发布候选）固定按本表跑 20 条端到端场景，对应指导书第 26 章验收清单与 27 章第 5、10 条要求。
> 与 [acceptance-matrix.md](acceptance-matrix.md) 的关系：本表是**执行流程**（每次 RC 逐条跑、填记录），验收矩阵是**证据索引**（每条场景对应哪些自动化测试）。
> 诚实口径：状态只写三类——`自动化通过`（本轮真实运行且通过）、`部分`（自动化只覆盖规则/单端，注入与真机未做）、`未执行`（无运行证据）。**有代码不等于通过，跳过不等于通过。**

## 0. 每次 RC 的执行方式

1. 先跑自动化门禁（下表 A 组），把输出贴进第 3 节执行记录。
2. 再按 B 组人工/真机步骤逐条执行；缺设备或凭证时如实记 `BLOCKED（原因）`，并标明阻断级别。
3. 任一条 FAIL 或 BLOCKED 高优项 → 该 RC 不可发布；降级方案见每行「失败出口」。

自动化门禁命令（仓库根）：

```bash
pnpm test:contract            # OpenAPI 破坏性变更
pnpm test:contract:asyncapi   # AsyncAPI 结构
pnpm test:joint               # API 层跨端一致性（6 场景）
pnpm test:security:deps       # 依赖漏洞（CRITICAL/HIGH 未评估即失败）
pnpm release:sbom             # CycloneDX SBOM 生成与自检
pnpm test:ops:backup          # 备份恢复演练
pnpm test:perf                # k6 冒烟（需 API 以 RL_*_MAX=0 启动）
pnpm test:e2e                 # Playwright 全栈（需 PG + JWT_SECRET + 8080 API）
cd services && ./mvnw -pl api -am test   # 后端 147 项集成/单测（MaaS 凭证缺失时 1 项跳过）
cd C:\ActionLoopBuild\apps\android && ./gradlew test   # Android JVM 96 项（必须 ASCII 路径）
```

## 1. 20 条场景清单

### A 组：自动化门禁（每次 RC 必跑）

| ID | 场景 | 步骤 | 预期 | 自动化证据 | 状态 |
| --- | --- | --- | --- | --- | --- |
| R01 | 通知→事项→提醒→完成→原文回溯 | Web 注册 → 分享/导入一条通知文本 → 确认候选 → 建事项与步骤 → 完成并上传凭证 → 回到原文 | 全链路成功，提醒与原文证据可追溯，AI 未直接改状态 | `MainJourneyE2EIntegrationTest`、`tests/e2e/main-journey.spec.ts` | 自动化通过 |
| R02 | OpenAPI 契约不破坏 | `pnpm test:contract` | 0 破坏性变更；Web 客户端无漂移；基线不可达时退出 2 | `tests/contract/openapi-contract.mjs`（67 路径基线） | 自动化通过 |
| R03 | AsyncAPI 实时契约 | `pnpm test:contract:asyncapi` | 2 channel / 4 message 结构完整，`$ref` 可解析 | `tests/contract/asyncapi-contract.mjs` | 自动化通过 |
| R04 | 跨端一致性（越权/并发/幂等） | `pnpm test:joint` | 6 场景全通过：他人事项 403/404 不泄露标题、旧 baseVersion 409、同 Idempotency-Key 不重复建 | `tests/joint/cross-client-consistency.mjs` | 自动化通过（API 层，非真机联测） |
| R05 | 依赖漏洞与 SBOM | `pnpm test:security:deps` + `pnpm release:sbom` | 0 未评估 CRITICAL/HIGH；放行项带理由与到期日；SBOM ≥50 组件且字段完整 | `tests/security/dependency-audit.mjs`、`scripts/release-sbom.mjs` | 自动化通过 |
| R06 | 备份恢复 | `pnpm test:ops:backup` | pg_dump -Fc → 空库 pg_restore → 逐表行数与整表 md5 一致 | `tests/ops/backup-restore-drill.mjs`（36 表 / 2946 行） | 部分（同实例新库；异地介质与干净机器未验证） |
| R07 | 性能冒烟 | `pnpm test:perf` | 按端点失败率 0；读接口 p95 在基线内 | `tests/performance/api-smoke.js` | 自动化通过（冒烟级，非容量验收） |

### B 组：产品闭环（26.1，需人工/真机/凭证）

| ID | 场景 | 步骤与预期 | 自动化证据 | 状态 / 失败出口 |
| --- | --- | --- | --- | --- |
| R08 | 通知去重与实质变更 | 同一 sourceKey 连发 3 次（原文相同→追加附件→改截止日期）：预期 1 条来源、候选按实质变更更新，不重复建事项 | `InboxChainIntegrationTest`、`RuleExtractorTest` | 部分（三连注入未做）；出口：保留手工合并 |
| R09 | 多日期与闲聊负样本 | 输入含两个日期/无截止日期的群消息：预期按规则拆分或标为无日期，闲聊不生成事项 | `RuleExtractorTest`、`CandidateExtractionServiceTest` | 部分（无真实 200 条评测集）；出口：用户手动设置日期 |
| R10 | 截图/文件→OCR→检索→转事项 | 导入长截图与扫描 PDF → 检索定位原文片段 → 情境重现 → 转事项 | `OcrIntegrationTest`、`LongScreenshotOcrTest`、`HybridSearchBehaviorIntegrationTest` | 部分（无文本层 PDF 样本与真实 MaaS 未跑）；出口：手动补原文 |
| R11 | 恶意文件拦截 | 上传假扩展名/可执行伪装/含宏 docx：预期 415、会话 FAILED、无证据落库 | `AssetContentInspectorTest`、`UploadIntegrationTest` | 自动化通过；边界：zip 仅按条目名检测不深扫 |
| R12 | 检索权限与删除一致性 | 成员 A 检索不到 B 私有原文；软删后列表与签名 URL 均不可再取 | `HybridSearchBehaviorIntegrationTest`、`PermissionIntegrationTest`、`SoftDeleteIntegrationTest` | 部分（旧签名到期后访问未单独断言） |
| R13 | 权威流程→材料/条件步骤→发布 | 建流程模板 → 绑来源片段 → 生成材料与条件步骤 → 非生成者复核发布 → 来源更新生成新版本且旧版本不可变 | `ProcessAssistIntegrationTest`、`SourceRecheckIntegrationTest` | 部分（真实两页来源与新旧版本并列未实测）；出口：标记「未核验」 |
| R14 | 来源抓取安全 | 对含内网地址/跳转/超大响应的 URL 复查：预期逐跳校验拒绝、robots 与限频生效、1MB 截断 | `SourceFetchServiceTest` | 自动化通过 |
| R15 | 多人空间全流程 | 邀请 → 分配/改派 → 评论 → 提交凭证 → 驳回 → 重新提交 → 核验 → 审计可查 | `TaskFlowIntegrationTest`、`ReviewEvidenceIntegrationTest`、`WorkspaceEventEndToEndIntegrationTest` | 部分（过期/转发邀请链接未覆盖） |
| R16 | 受控跨 App 高亮 | 受控测试 App 上高亮唯一目标 → 用户亲自点击 → 页面变化后重新匹配 → 低置信转文字 → 一键停止 | `TargetMatcherTest`、`GuidanceSessionManagerTest`、`GuidanceSessionStateTest`、`GuidanceTestAppContractTest` | 部分（真机页面变体有限）；**阻断发布** |
| R17 | 敏感页与撤权即停 | 密码框/FLAG_SECURE/支付页/锁屏/撤权：预期立即撤掉高亮与采集，无残留上传 | `SensitivePageDetectorTest`、`GuidanceSessionStateTest`、`AssistMediaPolicyTest` | 部分（真机敏感页与锁屏未验证）；**阻断发布** |
| R18 | 伪造控制事件拒绝 | 向协助会话注入 CLICK/SWIPE/INPUT/COMMIT_PAYMENT：预期协议拒绝并写审计，不产生任何系统操作 | `AssistMessageGuardTest`、`AssistSignalCoreTest`、`AssistScopesContractTest` | 自动化通过 |
| R19 | 协助授权与媒体不留存 | 单次授权 15 分钟到期 → 双方停止 → 检查存储/相册无原始帧 | `AssistScopesTest`、`AssistPayloadGuardTest`、`AssistMediaPolicyTest` | 部分（会话后存储检查未执行） |
| R20 | AI 故障降级 | 模拟超时/无效 JSON/配额耗尽/缓存命中：预期候选标注低置信、转手动编辑、不写事实、不改状态 | `AiResultCacheServiceTest`、`QwenAiProviderTest`、`AiOutputSchemaContractTest`、`AiExtractionTransactionIntegrationTest` | 部分（真实 MaaS 故障未注入）；出口：手动录入 |

## 2. 与第 26 章验收清单的对应

| 26 章条目 | 覆盖场景 |
| --- | --- |
| 26.1 一条通知闭环 | R01、R08 |
| 26.1 截图/文件记忆 | R10、R12 |
| 26.1 流程与来源 | R13、R14 |
| 26.1 多人空间 | R15、R04 |
| 26.1 跨 App 引导 | R16、R17 |
| 26.1 远程协助 | R18、R19 |
| 26.1 A–E 同一事项汇合 | R01（含证据/步骤/成员/提醒/完成） |
| 26.2 空/错/加载/离线/冲突状态 | R04、R09、R20 |
| 26.2 契约版本化 | R02、R03 |
| 26.2 离线重放与并发 | R04 |
| 26.2 AI 人工确认与降级 | R09、R20 |
| 26.2 监控/审计/备份/导出 | R06、R15 |
| 26.2 真机与干净安装 | R16、R17（未完成，阻断） |
| 26.3 禁自动操作 | R18 |
| 26.3 敏感页与撤权 | R17 |
| 26.3 越权测试 | R04、R12 |
| 26.3 无录像与日志脱敏 | R19 |
| 26.4 数据真实与脱敏 | R01（演示数据须标注） |

## 3. RC 执行记录（每次 RC 追加一行）

| RC 编号 | 日期 | 提交 | 自动化结果（R01–R07） | 人工/真机结果（R08–R20） | 未通过项与阻断级别 | 执行人 |
| --- | --- | --- | --- | --- | --- | --- |
| RC-2026-11-25 | 2026-11-25 | `47c4116` | R01–R07 自动化全部通过（后端 147 项/1 跳过、Web 32 项、E2E 2 项、joint 6/6、契约 PASS、审计 0 未评估、SBOM 159 组件、备份 36 表一致、k6 170 迭代 0 失败） | R11、R14、R18 自动化通过；R08–R10、R12、R13、R15、R19、R20 部分；R16、R17 需真机 | 阻断：R16、R17（缺 ≥2 台不同厂商真机）；非阻断：R06 异地备份、R20 真实 MaaS 故障注入 | 开发（本机） |

> 填写要求：日期用实际执行日；提交写被测 commit；结果只写「通过 / 部分（缺什么）/ FAIL / BLOCKED（缺什么）」；不得把未跑写成通过。
