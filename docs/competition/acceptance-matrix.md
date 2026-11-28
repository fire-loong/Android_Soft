# 核心测试矩阵验收映射（指导书 17.3）

> 目的：把指导书 17.3 的 40 条验收场景逐条映射到当前代码与自动化证据，作为竞赛材料与 RC 准出的对照表。
> 状态口径（保守）：
> - **已验证（自动化）**：存在直接覆盖该场景的自动化测试，且最近一次运行通过（见各阶段提交记录）。
> - **部分**：自动化只覆盖关键规则或单端，注入/真机/三端联合部分未验证。
> - **未验证**：无自动化，也无本轮运行证据。
> 本表不把「有代码」当成「已验证」。真实用户与真机场景见第 17.4 节，当前**未执行**。

| ID | 场景 | 实现位置 | 自动化证据 | 状态 |
| --- | --- | --- | --- | --- |
| A-01 | 手动分享闭环 | `SourceService` / `InboxService` / `TaskService` | `MainJourneyE2EIntegrationTest`、`apps/web/tests/e2e/main-journey.spec.ts` | 已验证（自动化） |
| A-02 | 通知去重 | `SourceService`（sourceKey + 实质变更） | `InboxChainIntegrationTest`、`tests/e2e` 同 key 用例；真机：1 条通知 → 1 条来源 1 条候选（2026-11-26） | 部分（同 key 更新序列未做三连注入） |
| A-03 | 多日期 | `RuleExtractor` / `CandidateExtractionService` | `RuleExtractorTest`、`CandidateExtractionServiceTest`；真机：含两日期的通知候选 `due_date` 留空、写入 `uncertainties_json`、`needs_user_confirmation=true`（2026-11-26） | 部分（无真实通知样本集评测） |
| A-04 | 无任务文本 | `RuleExtractor`、`SensitiveContentDetector` | `RuleExtractorTest`（含闲聊回归） | 已验证（规则层） |
| A-05 | 权限撤销 | Android 通知监听/发送权限流 | 真机：未勾选白名单时仅记包名、勾选后才上传；撤销只读无障碍后引导会话暂停（`PAUSE reason=a11y_revoked`，2026-11-26） | 部分（通知监听权限被系统撤销后的停止行为未验证） |
| A-06 | 重启和时区 | Android 提醒调度、`BootReceiver` | 真机 2026-11-26：重启后仍登录、事项与状态从缓存恢复；2026-11-28：未开自启动时开机广播被 vivo 拦截、0 条闹钟；**开「自启动」后重启，BootReceiver 重建两条 `REMINDER_*` 闹钟且登录态/事项完整** | 部分（提醒重建依赖 OEM 自启动权限，需在产品内提示；时区切换未验证；需多厂商设备） |
| B-01 | 长截图 | `OcrSegmentPlanner` / `OcrTextMerger` | `LongScreenshotOcrTest`、`OcrSegmentPlannerTest`、`OcrTextMergerTest` | 已验证（OCR 层） |
| B-02 | 扫描 PDF | `OcrIntegrationTest` 链路 | `OcrIntegrationTest` | 部分（无文本层 PDF 真实样本未跑） |
| B-03 | 文件去重 | `UploadIntegrationTest` 去重逻辑 | `UploadIntegrationTest` | 部分（跨端同名/异名同内容未联测） |
| B-04 | 恶意文件 | `AssetContentInspector`（魔数嗅探、可执行/OLE 拒绝、OOXML 宏工程拒绝、只跳压缩数据不解压、条目数上限）、`AssetService`（流式大小上限、失败会话落 FAILED） | `AssetContentInspectorTest`（7 项）、`UploadIntegrationTest`（假扩展名/可执行伪装/含 `vbaProject.bin` 的 docx 均 415，且断言会话 FAILED、无证据落库） | 已验证（自动化）；zip 仅按条目名检测，不深度扫描内嵌可执行/嵌套压缩；流式大小（data descriptor）zip 直接拒绝 |
| B-05 | 检索权限 | `HybridSearchService`（权限前置过滤） | `HybridSearchBehaviorIntegrationTest`、`PermissionIntegrationTest` | 已验证（自动化） |
| B-06 | 删除一致性 | `SoftDeleteIntegrationTest`、签名 URL 过期 | `SoftDeleteIntegrationTest` | 部分（旧签名到期后访问未单独断言） |
| C-01 | 官方流程 | `ProcessService` / 版本与步骤 | `ProcessAssistIntegrationTest` | 部分（条件材料真实两页来源未跑） |
| C-02 | 来源无信息 | 抽取不补全缺失字段 | `RuleExtractorTest`（缺失字段不臆造） | 部分 |
| C-03 | 来源冲突 | 流程版本并列差异 | `SourceRecheckIntegrationTest` | 部分（新旧官方版本并列选择未实测） |
| C-04 | SSRF | `SourceFetchService`（逐跳校验/内网拒绝/robots/限频/1MB） | `SourceFetchServiceTest` | 已验证（自动化） |
| C-05 | 来源变更 | 复查生成新版本、历史不被篡改 | `SourceRecheckIntegrationTest` | 部分（无真实公网页面变更验证） |
| D-01 | 邀请权限 | `WorkspaceService` / 邀请状态机 | `AuthIntegrationTest`、`PermissionIntegrationTest` | 部分（过期/转发链接用例未覆盖） |
| D-02 | 水平越权 | 服务端 ACL + 不泄露存在性 | `PermissionIntegrationTest`、`tests/joint/cross-client-consistency.mjs`（Android 身份读他人事项被拒且不泄露标题） | 已验证（自动化） |
| D-03 | 完成核验 | 完成/复核状态机与历史保留 | `TaskFlowIntegrationTest`、`ReviewEvidenceIntegrationTest` | 已验证（自动化） |
| D-04 | 并发冲突 | 版本号/乐观锁 | `TaskStateMachineTest`、`tests/joint/cross-client-consistency.mjs`（旧 baseVersion 返回 409） | 部分（双端同时改派/改期未压测） |
| D-05 | 离线幂等 | `IdempotencyFilter` / `sync_operation` | `IdempotencyIntegrationTest`、`tests/joint/cross-client-consistency.mjs`（同 key 重放不重复建） | 部分（进程被杀/断网恢复未注入） |
| E-01 | 正确高亮 | `TargetMatcher` + 受控测试 App | `TargetMatcherTest`、`GuidanceTestAppContractTest` | 部分（**本地目标高亮无 App 内入口**：`GuidanceSessionManager.setTarget` 无调用方，真机无法执行高亮/重匹配；2026-11-26 实测） |
| E-02 | 低置信 | 低置信转文字/人工 | `GuidanceSessionManagerTest`、`apps/web/tests/e2e` 低置信日期门 | 部分 |
| E-03 | 窗口变化 | `NormalizedBounds` 坐标归一 | `NormalizedBoundsTest`；真机：旋转触发 `DISPLAY_CHANGED` 并暂停（2026-11-26）；分屏可正常进入、不触发 `DISPLAY_CHANGED`（2026-11-28） | 部分（分屏下悬浮控制条不可见，需走通知栏停止；软键盘变化未验证） |
| E-04 | 敏感页面 | `SensitivePageDetector`（密码/FLAG_SECURE/支付/验证码） | `SensitivePageDetectorTest`；真机：密码弹窗即时暂停并写 `SENSITIVE_PAGE`、锁屏停止（2026-11-26）；桌面命中“支付”关键词也暂停（2026-11-28，疑似误伤 launcher） | 部分（支付页与独立 FLAG_SECURE 测试页未验证） |
| E-05 | 停止 | 悬浮条/通知/撤权/锁屏/超时 | `GuidanceSessionStateTest`；真机：悬浮条停止、锁屏停止、撤无障碍暂停（2026-11-26）、通知栏「停止引导」动作（2026-11-28 分屏下验证） | 部分（分屏下悬浮条不可见；超时未验证） |
| E-06 | 伪造控制 | 拒绝 CLICK/SWIPE/INPUT/COMMIT_PAYMENT | `AssistMessageGuardTest`、`AssistSignalCoreTest`、`AssistScopesContractTest` | 已验证（自动化） |
| E-07 | 弱网 | WebRTC 自适应、标注过期 | 无 | 未验证（需弱网注入） |
| E-08 | 媒体不留存 | `AssistMediaPolicy`（帧不落盘） | `AssistMediaPolicyTest` | 部分（会话后存储/相册检查未执行） |
| X-01 | 云 AI 故障 | 超时/无效 JSON/配额降级 | `AiResultCacheServiceTest`、`QwenAiProviderTest`、`AiOutputSchemaContractTest`、`CandidateExtractionServiceTest`；真机：真实 MaaS 两次 schema 失败曾致作业死信、通知被吞（2026-11-28 实测，已修：降级本地规则并写 `AI_OUTPUT_INVALID_FALLBACK`） | 部分（配额/超时故障未注入） |
| X-02 | TURN 故障 | TURN 凭证与失败提示 | 无 | 未验证（需 Coturn 环境） |
| X-03 | 数据库恢复 | `tests/ops/backup-restore-drill.mjs`（pg_dump -Fc → 空库 pg_restore → 逐表行数 + 整表 md5） | 本机演练 PASS：36 张表 / 2946 行 / 非空表 md5 一致 | 部分（同实例新库；干净机器与异地介质未验证） |
| X-04 | 大字体/读屏 | 200% 字号、TalkBack、键盘 | Android 布局修复 + JVM 单测；真机 200%：今天页标题溢出已修为单行省略、权限页按钮换行正常（2026-11-26） | 部分（无 TalkBack 实测） |
| X-05 | 桌面安装 | Tauri 安装器、中文路径、WebView2 | 无 | 未验证 |

## 汇总（共 35 条）

- 已验证（自动化）：9 条 —— A-01、A-04、B-01、B-04、B-05、C-04、D-02、D-03、E-06。
- 部分：23 条 —— 单端或规则层已覆盖，注入/真机/三端联合未验证；X-03 已有同实例恢复演练；A-02/A-03/A-05/A-06/E-01/E-03/E-04/E-05/X-04 已补 2026-11-26 vivo 真机证据。
- 未验证：3 条 —— E-07、X-02、X-05。
- 阻断发布的未验证项：E-07 弱网、X-02 TURN 故障（按 17.5 属 RC 前置条件）；X-03 需再补干净机器/异地介质演练；E-01 本地目标高亮需先补 App 内入口（`setTarget` 未接线）。

## 附录：不在 17.3 编号内、但属 26.2 验收的两项

> 这两项在指导书中位于 11.7 / 13.2 / 15.3 / 15.5 / 26.2，不占用上表 35 条编号，故单列。统计仍以上表 35 条为准。

| 能力 | 实现位置 | 自动化证据 | 本机运行证据 | 状态与边界 |
| --- | --- | --- | --- | --- |
| 空间数据导出（异步 + 密码二次认证 + 24h 短期下载） | `ExportRequestService`（密码校验失败写 DENIED 审计并 401）、`ExportService`（`PENDING` 入队 `EXPORT_BUILD`、悲观行锁、`expiresAt=+24h`、过期返回 410）、`ExportJobHandler`、`ExportCleanupJob`（每小时清理过期对象） | `ExportIntegrationTest` 3 项（二次认证+异步+下载、成员/陌生人隔离、过期清理）；`tests/e2e/main-journey.spec.ts` 第 4 条（Web 密码门 401→202 PENDING） | API + 独立 worker + PG17：无密码 400、错密码 401 且不建任务；正确密码 202 → 531ms READY（`jobs.type=EXPORT_BUILD state=SUCCEEDED attempts=1`）；下载 2285 字节 JSON 含刚建事项；其他用户 404；审计含 DENIED/SUCCESS | 已验证（自动化 + 本机演练）；边界：E2E 不启动 worker（READY 由集成测试与演练覆盖），未做多实例并发导出压测 |
| 账户注销（冷却期申请/取消/到期清除） | `AccountDeletionService`（`DELETE /me` 202、`/me/deletion/cancel`、共享空间所有者 409）、`AccountPurgeService` + worker `AccountDeletionPurgeJob` | `AccountDeletionIntegrationTest` 4 项、`AccountPurgeIntegrationTest` 3 项；`tests/e2e/main-journey.spec.ts` 第 3 条（申请→取消） | API + 独立 worker + PG17（`COOLDOWN_DAYS=0`、扫描 2s）：2s 内清除，worker 日志 `account purged ... workspaces=1 devices=1`；旧 access/refresh/密码全部 401；同邮箱可重新注册（匿名化生效） | 已验证（自动化 + 本机演练）；边界：多 worker 并发清除与异地备份中的残留副本未验证 |

## 待用户/环境提供

- 真机与厂商覆盖（A-06/E-03 分屏/E-04 支付页/X-04）：已有 1 台 vivo V2352A（Android 16）实测记录，仍需 ≥2 台不同厂商设备。
- 真实 MaaS 凭证与配额（X-01、B-02）：用于 AI/OCR 真实链路验证。
- Coturn 服务器与双端网络（E-07、X-02）。
- 备份介质与恢复窗口（X-03）。
