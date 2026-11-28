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
pnpm test:ops:backup          # 备份恢复演练（pg_dump -Fc → 空库 pg_restore → 校表）
pnpm test:ops:fault           # 故障注入：杀 API 进程 → 重启 → 校验数据保留与 Idempotency-Key 重放
pnpm test:perf                # k6 冒烟（需 API 以 RL_*_MAX=0 启动）
pnpm test:e2e                 # Playwright 全栈（需 PG + JWT_SECRET + 8080 API）
cd services && ./mvnw -pl api -am test   # 后端 156 项集成/单测（MaaS 凭证缺失时 1 项跳过）
cd C:\ActionLoopBuild\apps\android && ./gradlew test   # Android JVM 103 项（必须 ASCII 路径）
```

## 1. 20 条场景清单

### A 组：自动化门禁（每次 RC 必跑）

| ID | 场景 | 步骤 | 预期 | 自动化证据 | 状态 |
| --- | --- | --- | --- | --- | --- |
| R01 | 通知→事项→提醒→完成→原文回溯 | Web 注册 → 分享/导入一条通知文本 → 确认候选 → 建事项与步骤 → 完成并上传凭证 → 回到原文 | 全链路成功，提醒与原文证据可追溯，AI 未直接改状态 | `MainJourneyE2EIntegrationTest`、`tests/e2e/main-journey.spec.ts` | 自动化通过 |
| R02 | OpenAPI 契约不破坏 | `pnpm test:contract` | 0 破坏性变更；Web 客户端无漂移；基线不可达时退出 2 | `tests/contract/openapi-contract.mjs`（68 路径基线） | 自动化通过 |
| R03 | AsyncAPI 实时契约 | `pnpm test:contract:asyncapi` | 2 channel / 4 message 结构完整，`$ref` 可解析 | `tests/contract/asyncapi-contract.mjs` | 自动化通过 |
| R04 | 跨端一致性（越权/并发/幂等） | `pnpm test:joint` | 6 场景全通过：他人事项 403/404 不泄露标题、旧 baseVersion 409、同 Idempotency-Key 不重复建 | `tests/joint/cross-client-consistency.mjs` | 自动化通过（API 层，非真机联测） |
| R05 | 依赖漏洞与 SBOM | `pnpm test:security:deps` + `pnpm release:sbom` | 0 未评估 CRITICAL/HIGH；放行项带理由与到期日；SBOM ≥50 组件且字段完整 | `tests/security/dependency-audit.mjs`、`scripts/release-sbom.mjs` | 自动化通过 |
| R06 | 备份恢复与杀进程 | `pnpm test:ops:backup`：pg_dump -Fc → 空库 pg_restore → 逐表行数与整表 md5 一致；`pnpm test:ops:fault`：硬杀 API → 重启 → 事项/状态/令牌保留、同 Idempotency-Key 重放返回同一事项 | 不丢已确认数据，幂等记录跨重启有效 | `tests/ops/backup-restore-drill.mjs`（36 表 / 2946 行）、`tests/ops/fault-injection-drill.mjs`（3/3 通过） | 部分（备份为同实例新库、杀进程为本机；异地介质与 DB 重启未验证） |
| R07 | 性能冒烟 | `pnpm test:perf` | 按端点失败率 0；读接口 p95 在基线内 | `tests/performance/api-smoke.js` | 自动化通过（冒烟级，非容量验收） |

### B 组：产品闭环（26.1，需人工/真机/凭证）

| ID | 场景 | 步骤与预期 | 自动化证据 | 状态 / 失败出口 |
| --- | --- | --- | --- | --- |
| R08 | 通知去重与实质变更 | 同一 sourceKey 连发 3 次（原文相同→追加附件→改截止日期）：预期 1 条来源、候选按实质变更更新，不重复建事项 | `InboxChainIntegrationTest`、`RuleExtractorTest` | 部分（真机单次注入已验证：1 来源 1 候选；三连实质变更未做）；出口：保留手工合并 |
| R09 | 多日期与闲聊负样本 | 输入含两个日期/无截止日期的群消息：预期按规则拆分或标为无日期，闲聊不生成事项 | `RuleExtractorTest`、`CandidateExtractionServiceTest` | 部分（真机已验证双日期→`uncertainties_json` 记“存在两个日期…无法确定关系”、`due_date` 留空、`needs_user_confirmation=true`；无真实 200 条评测集）；出口：用户手动设置日期 |
| R10 | 截图/文件→OCR→检索→转事项 | 导入长截图与扫描 PDF → 检索定位原文片段 → 情境重现 → 转事项 | `OcrIntegrationTest`、`LongScreenshotOcrTest`、`HybridSearchBehaviorIntegrationTest` | 部分（无文本层 PDF 样本与真实 MaaS 未跑）；出口：手动补原文 |
| R11 | 恶意文件拦截 | 上传假扩展名/可执行伪装/含宏 docx：预期 415、会话 FAILED、无证据落库 | `AssetContentInspectorTest`、`UploadIntegrationTest` | 自动化通过；边界：zip 仅按条目名检测不深扫 |
| R12 | 检索权限与删除一致性 | 成员 A 检索不到 B 私有原文；软删后列表与签名 URL 均不可再取 | `HybridSearchBehaviorIntegrationTest`、`PermissionIntegrationTest`、`SoftDeleteIntegrationTest` | 部分（旧签名到期后访问未单独断言） |
| R13 | 权威流程→材料/条件步骤→发布 | 建流程模板 → 绑来源片段 → 生成材料与条件步骤 → 非生成者复核发布 → 来源更新生成新版本且旧版本不可变 | `ProcessAssistIntegrationTest`、`SourceRecheckIntegrationTest` | 部分（真实两页来源与新旧版本并列未实测）；出口：标记「未核验」 |
| R14 | 来源抓取安全 | 对含内网地址/跳转/超大响应的 URL 复查：预期逐跳校验拒绝、robots 与限频生效、1MB 截断 | `SourceFetchServiceTest` | 自动化通过 |
| R15 | 多人空间全流程 | 邀请 → 分配/改派 → 评论 → 提交凭证 → 驳回 → 重新提交 → 核验 → 审计可查 | `TaskFlowIntegrationTest`、`ReviewEvidenceIntegrationTest`、`WorkspaceEventEndToEndIntegrationTest` | 部分（过期/转发邀请链接未覆盖） |
| R16 | 受控跨 App 高亮 | 受控测试 App 上高亮唯一目标 → 用户亲自点击 → 页面变化后重新匹配 → 低置信转文字 → 一键停止 | `TargetMatcherTest`、`GuidanceSessionManagerTest`、`GuidanceSessionStateTest`、`GuidanceTestAppContractTest` | 部分（真机已验证会话启动、悬浮层只读提示、敏感页暂停、一键停止；**本地目标高亮在 App 内无 UI 入口**——`GuidanceSessionManager.setTarget` 全工程无调用方，受控测试 App 的高亮/重新匹配/低置信降级无法在真机执行）；**阻断发布** |
| R17 | 敏感页与撤权即停 | 密码框/FLAG_SECURE/支付页/锁屏/撤权：预期立即撤掉高亮与采集，无残留上传 | `SensitivePageDetectorTest`、`GuidanceSessionStateTest`、`AssistMediaPolicyTest` | 部分（真机 2026-11-26 已验证：密码弹窗即时暂停并清标注、锁屏停止采集（审计 `SENSITIVE_PAGE(锁屏状态)` + `STOP reason=user_revoked`，即系统锁屏撤销了 MediaProjection）、撤销只读无障碍即时暂停（审计 `PAUSE reason=a11y_revoked`；屏幕共享授权仍有效，故采集继续）、悬浮层一键停止；支付页与独立 FLAG_SECURE 测试页未验证）；**阻断发布** |
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
| 26.2 监控/审计/备份/导出 | R06、R15（数据导出与注销另见 `ExportIntegrationTest`（3 项）、`AccountDeletionIntegrationTest` 与本机 API+worker 实测记录） |
| 26.2 进程被杀/离线恢复 | R06（杀进程）、R04（幂等重放） |
| 26.2 真机与干净安装 | R16、R17（未完成，阻断） |
| 26.3 禁自动操作 | R18 |
| 26.3 敏感页与撤权 | R17 |
| 26.3 越权测试 | R04、R12 |
| 26.3 无录像与日志脱敏 | R19 |
| 26.4 数据真实与脱敏 | R01（演示数据须标注） |

## 3. RC 执行记录（每次 RC 追加一行）

| RC 编号 | 日期 | 提交 | 自动化结果（R01–R07） | 人工/真机结果（R08–R20） | 未通过项与阻断级别 | 执行人 |
| --- | --- | --- | --- | --- | --- | --- |
| RC-2026-11-25 | 2026-11-25 | `47c4116` | R01–R07 自动化全部通过（后端 147 项/1 跳过、Web 32 项、E2E 2 项、joint 6/6、契约 PASS、审计 0 未评估、SBOM 159 组件、备份 36 表一致、杀进程 3/3、k6 170 迭代 0 失败） | R11、R14、R18 自动化通过；R08–R10、R12、R13、R15、R19、R20 部分；R16、R17 需真机 | 阻断：R16、R17（缺 ≥2 台不同厂商真机）；非阻断：R06 异地备份、R20 真实 MaaS 故障注入 | 开发（本机） |
| RC-2026-11-26（真机增量） | 2026-11-26 | `4535a59` | 只复跑受影响门禁：Android JVM 96 项通过、REBUILD 后 `assembleDebug` 成功；后端/Web/E2E/契约/依赖审计/备份与杀进程演练**未复跑**，不构成完整 RC | vivo V2352A（Android 16，SDK 36）真机：通知→来源→AI 候选→确认建事项→提交完成凭证（待复核）；密码弹窗暂停、锁屏停止、旋转/分辨率变化暂停、撤销只读无障碍暂停、悬浮层一键停止 | 阻断：R16 本地目标高亮在 App 内无入口；R17 支付页与独立 FLAG_SECURE 页未验证 | 开发（本机 + 真机） |

> 填写要求：日期用实际执行日；提交写被测 commit；结果只写「通过 / 部分（缺什么）/ FAIL / BLOCKED（缺什么）」；不得把未跑写成通过。

### 3.1 真机执行明细（2026-11-26，vivo V2352A / Android 16 / 1260×2800@560dpi）

环境：debug APK 指向本机 API（`adb reverse tcp:8080`），API 与 worker（jar）均以真实进程运行，PG 17 + Redis 本机；账号为测试账号「vivo-demo 的个人空间」。截图保存在 `build/device/`（二进制不入库）。

| 步骤 | 操作 | 结果 |
| --- | --- | --- |
| 通知采集授权 | `cmd notification allow_listener` 授权后，本地白名单未勾选时只记包名（DataStore `seen_packages=com.android.shell`），勾选后才上传 | 通过（默认拒绝生效） |
| 通知→来源 | 注入 1 条含双日期的通知（“本周六上午 9 点停水…截止时间 2026-11-28 12:00”） | 通过：`source_items` 1 条，`SourceUploadWorker` SUCCESS，job `SOURCE_AI_EXTRACTION` SUCCEEDED |
| AI 候选（R09） | worker 抽取候选「停水检修储水准备」 | 通过：`derivation=AI`、`confidence=0.8`、`due_date` 为空、`uncertainties_json=["存在两个日期…无法确定其关系"]`、`needs_user_confirmation=true`（未自动写事实） |
| 确认建事项（R01 片段） | 待确认页点击候选 → 「确认创建」 | 通过：候选 CONFIRMED → 生成事项（步骤 1 条、原文证据 1 条），「今天」页可见 |
| 完成凭证 | 事项详情 → 凭证 → 填完成说明 → 「提交完成凭证」 | 通过：步骤变「待复核」，`completions` 1 条（未自动判定完成） |
| 引导会话启动 | 授权悬浮层 + 只读无障碍 → 「启动一次引导」→ 系统屏幕共享授权（整个屏幕） | 通过：悬浮层显示「引导：仅文字…」+ 暂停/求助/停止，前台通知「屏幕共享中」 |
| 敏感页（R17） | 受控测试 App → 「安全输入演示」 | 通过：悬浮层转「已暂停」并清标注，审计 `SENSITIVE_PAGE`（检测到密码/可编辑敏感字段） |
| 旋转（R17） | `settings put system user_rotation 1` | 通过：审计 `DISPLAY_CHANGED`，会话暂停，旋转回竖屏不影响已停会话 |
| 锁屏（R17） | `input keyevent 223/26` | 通过：审计 `SENSITIVE_PAGE(锁屏状态)` + `STOP reason=user_revoked`（锁屏使系统撤销 MediaProjection），`ScreenCaptureService` 退出。未观察到走 `lockscreen` 分支的审计 |
| 撤无障碍（R17，本轮修复） | 会话进行中 `settings put secure enabled_accessibility_services ''` | **修复前 FAIL**：会话仍显示进行中且不再产生帧；**修复后 PASS**：立刻转「已暂停」，审计 `PAUSE reason=a11y_revoked`，点「恢复」提示先重新开启无障碍且不恢复。注意：屏幕共享授权仍然有效，采集与「屏幕共享中」通知继续（不属于违例） |
| 一键停止（R16 片段） | 悬浮层「停止」 | 通过：悬浮层消失、状态栏共享标识消失、`ScreenCaptureService` 停止；审计先 `STOP reason=user_overlay_stop`，采集服务收到停止动作后再补一条 `STOP reason=notification_stop` |
| 覆盖安装 | `adb install -r` 新 APK | 通过：登录态与安全审计等本地偏好保留 |
| 撤通知监听（A-05） | `cmd notification disallow_listener …` 后再注入一条通知 | 通过：监听器不再出现在 Allowed/Live 列表，`source_items` 仍为 1 条（无可上传证据产生） |
| 大字体 200%（X-04） | `settings put system font_scale 2.0` 后逐页检查 | 通过（修复后）：「今天」页空间名溢出压住首卡 → 已改单行省略；权限页按钮换行正常、无重叠。TalkBack 未实测 |
| 重启（A-06 片段） | `adb reboot` 后重新连接 Wi-Fi 调试并重建 `adb reverse` | 通过：仍保持登录，事项与「进行中」状态从本地缓存恢复且无离线标记（API 可达）。注：重启后 `enabled_accessibility_services` 被清空（vivo 行为，需重新授权）；提醒闹钟重建与省电策略未验证 |

本轮真机发现：
1. **已修**：撤销只读无障碍不会停止引导（违反 8.10 撤权即停）→ `GuidanceAccessibilityService.onUnbind` 用独立 scope 暂停会话（避免随 `onDestroy` 的 `scope.cancel()` 丢事件）；悬浮层「恢复」在无障碍关闭时拒绝恢复并给出提示，「待再次同意」状态同样拦截。
2. **已修（R16，见 3.6）**：本地目标高亮原先无 App 内入口——`GuidanceSessionManager.setTarget` 在 `apps/android` 内没有调用方。已在权限页补「本地目标高亮（受控测试 App）」卡片并在真机跑通「设置目标→高亮→用户点击→重新匹配→低置信降文字→停止」。
3. 观察项：本地候选抽取在 App 内走后端 worker（`SOURCE_AI_EXTRACTION`）；API 单进程运行时通知只会停留在 PENDING 作业，验证链路必须同时运行 worker。
4. **已修（布局）**：200% 字号下「今天」页空间名超出 TopAppBar 与首卡重叠 → 标题单行省略。

### 3.2 真机追加（2026-11-28，同一台 vivo）

| 步骤 | 操作 | 结果 |
| --- | --- | --- |
| 提醒确认（R01/A-06） | 注入含单一日期「截止时间 11月28日 13:00」的通知 → 待确认页勾选提醒 → 确认创建 | **修复前 FAIL**：`ReminderService.replace` 抛「提醒时间与渠道不能为空」，事务回滚并返回 500（客户端把 `channel` 当默认值省略，服务端 Jackson 收到 null）。**修复后 PASS**：事项创建、`reminders` 一条 `SCHEDULED`、`dumpsys alarm` 出现 `com.actionloop.android.REMINDER_*`（未授予精确闹钟，窗口为不精确：15min/1h） |
| 无效 AI 输出（R20） | 同内容通知重发 | **修复前 FAIL**：MaaS 连续两次 schema 校验失败 → 作业 `DEAD_LETTER / AI_OUTPUT_INVALID`，App 待确认页为空，用户无任何提示（通知被吞）。**修复后**：降级本地规则并写 `AI_OUTPUT_INVALID_FALLBACK` 警告（JVM 单测 8 项覆盖；真实模型未再复现无效输出，待后续观察） |
| 重启后提醒重建（A-06） | 重启设备 | **首测 FAIL**：`pidof` 无 App 进程、0 条 `REMINDER_*` 闹钟，`BOOT_COMPLETED` 被 vivo 自启动限制拦截（adb shell 不能代发开机广播）。**开启「自启动」后复测 PASS**：重启后 App 进程由开机广播拉起（pid 8804），`dumpsys alarm` 重新出现两条 `REMINDER_*`（`when`=11:00/13:00，仍为不精确窗口），打开 App 仍登录、今天页事项与状态完整。**结论：重建逻辑正确，但依赖 OEM 自启动权限，需在权限页/文档中提示用户开启** |

### 3.3 分屏、桌面与敏感页（2026-11-28，同一台 vivo）

| 步骤 | 操作 | 结果 |
| --- | --- | --- |
| 分屏（E-03） | 会话进行中进入最近任务 → 长按卡片 → 分屏 → 选第二个应用 | 分屏成功，ActionLoop 上半屏正常显示；**未触发 `DISPLAY_CHANGED`**（物理显示未变，只有窗口变化） |
| 悬浮条可见性（E-05） | 分屏后观察 | **FAIL（P1 可用性）**：悬浮控制条窗口仍存在（`type=2038`，frame 在上半屏），但界面上不可见，用户无法点「停止」 |
| 替代停止路径（E-05） | 通知栏展开「ActionLoop 引导进行中」→「停止引导」 | 通过：`ScreenCaptureService` 退出、共享标识消失。注：直接点通知正文（“点此停止”）不会停止，必须展开后点「停止引导」动作 |
| 外部停止尝试 | `am startservice -a …STOP_CAPTURE` | 通过（符合设计）：服务未导出，外部 uid 被拒绝，无法绕过 App 内授权路径 |
| 桌面敏感词（E-04 边界） | 回到 `com.bbk.launcher2` 主屏并逐页观察 | **修复后通过**：launcher 包名跳过支付等文案关键词，主屏不再误暂停，审计无新增 launcher `SENSITIVE_PAGE`；锁屏/FLAG_SECURE/密码字段硬判定仍生效；远程高亮/指针/文字共用同一判定并有 JVM 回归测试。新发现：桌面全局搜索是独立包 `com.vivo.globalsearch`，搜索「支付宝」仍命中关键词并暂停，尚未按 launcher 一并豁免，避免把非桌面内容笼统放行（已于 3.6 修复复测） |
| 支付安全页（E-04） | 打开受控 `PaymentActivity`（真实 FLAG_SECURE + 支付文案，无交易）→ 恢复引导 | 通过：页面内容被系统涂黑，仅悬浮条可见；写 `SENSITIVE_PAGE|com.actionloop.guidancetest: 命中敏感关键词: 支付` 并暂停，无高亮残留 |
| 独立 FLAG_SECURE（E-04） | 打开不含敏感词/输入框的 `SecureActivity` → 恢复引导 | **修复前 FAIL**：系统栏与悬浮条亮像素使全屏黑帧比例达不到 1%，会话仍运行。**最终修复后通过**：改为 40 个水平采样行统计，至少 20% 基本全黑行即命中，避免系统栏/悬浮条/中央弹窗/IME 的亮区稀释；写 `CAPTURE_SECURE|FLAG_SECURE frame`，悬浮条立即变为「已暂停」（最终审计时间戳 `1795839624346`）。`SecureFrameDetectorTest` 覆盖 80% 区域被遮挡、亮页、零星黑行与截断缓冲 |
| 离开安全页恢复（E-04） | 返回普通 launcher 主屏 → 手动「恢复」 | 通过：会话回到「仅文字」运行；不会因上一轮安全窗口永久锁死 |
>
> 增量说明（不入正式 RC 记录）：`dc7ee9c`（导出异步+二次认证）之后只复跑了受影响门禁——后端 156 项、Web 32 项 + `tsc`、`pnpm test:contract`（68 路径）、Web E2E 4 条；k6、joint、供应链扫描、备份/杀进程演练未复跑，因此不构成一次新的完整 RC。

### 3.4 TalkBack 与时区（2026-11-28，同一台 vivo）

| 步骤 | 操作 | 结果 |
| --- | --- | --- |
| 双无障碍服务（X-04） | 保留 ActionLoop 只读服务并启用 TalkBack 16.0，`dumpsys accessibility` 检查 | 部分通过：两服务同时 Bound，TalkBack `touchExplorationEnabled=true`，ActionLoop 服务未崩溃；权限页的返回、启动/停止引导、求助及采集开关均有文本/状态语义，右滑后出现返回键焦点框。**未完整验证语音朗读、线性焦点遍历全页和双击激活，不宣称 TalkBack 场景通过**；验后已恢复原无障碍列表，`touchExplorationEnabled=false` |
| 时区切换（A-06） | 记录 `dumpsys alarm` → `cmd alarm set-timezone Asia/Tokyo` → 恢复 `Asia/Shanghai` | 通过（单机的系统调度层）：同一提醒 `origWhen=1795842000000` 始终不变，活动 ActionLoop 闹钟保持 1 条；东京时区显示 14:00、恢复上海显示 13:00，绝对触发时刻与剩余 elapsed 窗口未被重置。提醒使用 `RTC_WAKEUP` 绝对 Instant，无需新增时区广播接收器 |

### 3.5 分屏悬浮条修复回归（2026-11-28，同一台 vivo）

| 步骤 | 操作 | 结果 |
| --- | --- | --- |
| 修复与自动化 | `MainActivity` 接收实际窗口配置变化，`decorView.post` 后调用 `GuidanceOverlay.restoreWindowOrder()`；高亮层/控制条按原顺序 `removeViewImmediate` 并重挂载，失败则停止采集 | 通过：`:app:testDebugUnitTest :app:assembleDebug --no-configuration-cache` 在 ASCII 构建路径成功，Android JVM `tests=103 failures=0 errors=0 skipped=0`；新增 `GuidanceOverlayTest` 验证同一 View 重挂载与顺序。仅复跑 Android 门禁，不构成完整 RC |
| 第一轮分屏 | 修复 APK 上通过最近任务卡片菜单进入真实分屏 | 单机通过：窗口 ID 由 `6a22edf/dcb31c9` 变为 `1189680/952bf05`，`build/device/split-final-1.png` 中控制条真实可见；退出分屏后再次重挂载，采集服务仍存活 |
| 第二轮分屏（E-03/E-05） | 再次通过最近任务进入分屏，等待第三方横幅消失后观察并截图 | 单机通过：两个 `type=2038` 窗口 ID 由 `c80bc4f/1dddc44` 变为 `492c40b/7a53bb8`，`build/device/split-final-2.png` 中「引导：已暂停」及恢复/求助/停止控制条真实可见；不是仅凭 `isVisible=true` 或服务存活判定 |
| 分屏内停止（E-05） | 在第二轮分屏内点击悬浮「停止」 | 单机通过：审计依次写入 `1795844238400|STOP|reason=user_overlay_stop`、`1795844238431|STOP|reason=notification_stop`；`ScreenCaptureService` 与两个 ActionLoop `2038` 窗口均消失，`build/device/split-stopped.png` 显示分屏仍保持 |
| 覆盖边界 | 对照修复前复测与设备范围 | 原始 P1 在本机两轮回归与分屏内停止已闭环，但原问题是间歇性：未修复版一次干净复测也曾出现控制条可见（`split-p1-real.png`），不能宣称必现或仅凭两轮证明所有时序均已覆盖；本轮仅覆盖 vivo V2352A / Android 16，其他厂商、软键盘与超时仍未验证，多设备覆盖仍是 RC 前置阻断 |

### 3.6 R16 入口、搜索语义与安全边界收口（2026-11-28，同一台 vivo V2352A / Android 16）

构建与自动化（ASCII 路径 `C:\ActionLoopBuild\apps\android`）：`.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :guidancetest:assembleDebug --no-configuration-cache` 成功；Android JVM `tests=122 failures=0 errors=0 skipped=0`（新增 `GuidanceSessionTimeoutTest`、`GuidanceSessionManagerTest` 目标用例、`SensitivePageDetectorTest` 搜索页/转场宽限用例）。以下均为**最终构建**安装后的真机操作记录，操作全部由测试者点击完成，产品端只读、只渲染。

| 项 | 操作 | 结果 |
| --- | --- | --- |
| R16 入口（E-01） | 权限页「本地目标高亮（受控测试 App）」卡片 → 「目标：下一步」 | 通过：Toast「已设置目标「下一步」，请切换到受控测试 App 查看」，「当前目标：下一步」；审计 `1795854213201\|LOCAL_TARGET_SET\|pkg=com.actionloop.guidancetest label=下一步`。按钮仅在会话活跃（且本地目标需无障碍已开启）时可用，未开启会话时点击给出明确提示而非静默失败 |
| R16 高亮（E-01） | 切到受控演示页 `GuidanceDemoActivity` | 通过：绿色描边高亮唯一「下一步」，悬浮条「引导：高亮中」；审计 `LOCAL_MATCH frame=5a301743-… score=1.00` |
| R16 点击后重匹配（E-01） | 测试者本人点击高亮的「下一步」 | 通过：页面文案变「步骤：1 · 点击「下一步」」，新帧 `LOCAL_MATCH frame=6fbdf52a-… score=1.00` 重新高亮；再次切回演示页又生成 `75a35cdb-… score=1.00`，说明按 frameId 重新匹配、旧标注失效路径生效 |
| R16 低置信降文字（E-01） | 回 App 设置「低置信：确认提交」（`1795854326673`）→ 再切演示页 | 通过：仅悬浮条「引导：仅文字…」，页面无任何描边，审计无新的 `LOCAL_MATCH`（未达阈值不猜目标） |
| R16 停止 | 悬浮层「停止」 | 通过：审计 `1795854354951\|STOP\|reason=user_overlay_stop` + `notification_stop`，`ScreenCaptureService` 记录消失 |
| 搜索误暂停（E-04） | 会话进行中 → 桌面下拉打开 `com.vivo.globalsearch` → 输入 `zhifubao` 回车 | 通过：搜索首页与结果页（含“支付宝”“支付宝付款码”等）悬浮条保持「引导：仅文字…」，审计无 `SENSITIVE_PAGE`。**复测中发现并修复**：搜索窗口刚出现时 `windows` 层级里尚无该包（实测只有 launcher/upslide 等系统层），转场单帧仍会把“支付”误判为敏感页；现改为「搜索聚合包 + 探测不到该包窗口 → 最多 1.5s 转场宽限（`SEARCH_SURFACE_GRACE` 审计）」，窗口可枚举后仍要求顶部搜索框结构证据；密码框 / FLAG_SECURE / 支付页硬判定不受影响（JVM 3 例覆盖：转场豁免、非搜索包不豁免、宽限下硬判定生效）。已知边界：搜索包内若存在“带顶部搜索框的具体内容页”，关键词同样不会触发暂停（该包内无真实支付表单；密码/FLAG_SECURE/锁屏硬判定仍生效），需在后续按页面语义再细分 |
| 软键盘（E-03 边界） | 全局搜索页 IME 弹出时观察 | 单机通过（非分屏）：悬浮条仍可见，未被 IME 遮挡或误暂停 |
| 支付页硬判定（E-04） | 打开受控 `PaymentActivity`（真实 FLAG_SECURE + 支付文案） | 通过：审计 `1795853970367\|SENSITIVE_PAGE\|com.actionloop.guidancetest: 命中敏感关键词: 支付`，悬浮条「引导：已暂停…」 |
| 独立 FLAG_SECURE（E-04） | 打开不含敏感词/输入框的 `SecureActivity` 并「恢复」 | 通过：约 4s 内写 `1795854012406\|CAPTURE_SECURE\|FLAG_SECURE frame` 并暂停（1.2s 持续黑场闩锁 + 2s 启动预热宽限） |
| 普通切换不误暂停（E-04 回归） | 「恢复」后 14s 内在受控主页/演示页/本 App 之间切换 3 次 | 通过：状态保持「仅文字…」运行，无新增 `CAPTURE_SECURE`（验证恢复边沿清零 + 先渲染后判定的修改） |
| 分辨率变化（E-03 边界） | 会话进行中 `wm size 1080x2340`，随后 `wm size reset` | 通过（符合设计，上一构建）：写 `1795851841584\|DISPLAY_CHANGED\|rotation/resolution changed` 并暂停，悬浮条按新尺寸重新布局；reset 后恢复原尺寸，仍为暂停态等待用户「恢复」 |
| 系统策略观察 | 在系统设置页观察悬浮层 | 说明：`dumpsys window` 显示 `mForceHideNonSystemOverlayWindow=true`，系统主动隐藏非系统悬浮层（窗口仍存在）；离开设置页后悬浮条自动恢复，属于 OS 防劫持策略，不是产品缺陷，需在演示脚本中避免在设置页要求点按悬浮条 |

仍未覆盖（如实记录）：分屏内的软键盘组合（本轮 adb 手势无法稳定进入分屏，未重测）、其他厂商/其他分辨率组合（仅一台 vivo V2352A / Android 16）、15 分钟真实超时（仅 `GuidanceSessionTimeoutTest` JVM 边界覆盖；真机等待成本高，未跑）。
