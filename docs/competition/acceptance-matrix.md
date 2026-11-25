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
| A-02 | 通知去重 | `SourceService`（sourceKey + 实质变更） | `InboxChainIntegrationTest`、`tests/e2e` 同 key 用例 | 部分（同 key 更新序列未做三连注入） |
| A-03 | 多日期 | `RuleExtractor` / `CandidateExtractionService` | `RuleExtractorTest`、`CandidateExtractionServiceTest` | 部分（无真实通知样本集评测） |
| A-04 | 无任务文本 | `RuleExtractor`、`SensitiveContentDetector` | `RuleExtractorTest`（含闲聊回归） | 已验证（规则层） |
| A-05 | 权限撤销 | Android 通知监听/发送权限流 | 无 | 未验证（需真机撤销权限） |
| A-06 | 重启和时区 | Android 提醒调度 | 无 | 未验证（需多厂商真机 + 省电策略） |
| B-01 | 长截图 | `OcrSegmentPlanner` / `OcrTextMerger` | `LongScreenshotOcrTest`、`OcrSegmentPlannerTest`、`OcrTextMergerTest` | 已验证（OCR 层） |
| B-02 | 扫描 PDF | `OcrIntegrationTest` 链路 | `OcrIntegrationTest` | 部分（无文本层 PDF 真实样本未跑） |
| B-03 | 文件去重 | `UploadIntegrationTest` 去重逻辑 | `UploadIntegrationTest` | 部分（跨端同名/异名同内容未联测） |
| B-04 | 恶意文件 | `AssetContentInspector`（魔数嗅探、可执行/OLE 拒绝、OOXML 宏工程拒绝）、`AssetService`（流式大小上限、服务端从不解压） | `AssetContentInspectorTest`（6 项）、`UploadIntegrationTest`（假扩展名/可执行伪装/含 `vbaProject.bin` 的 docx 均 415） | 已验证（自动化） |
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
| E-01 | 正确高亮 | `TargetMatcher` + 受控测试 App | `TargetMatcherTest`、`GuidanceTestAppContractTest` | 部分（真机页面变体有限） |
| E-02 | 低置信 | 低置信转文字/人工 | `GuidanceSessionManagerTest`、`apps/web/tests/e2e` 低置信日期门 | 部分 |
| E-03 | 窗口变化 | `NormalizedBounds` 坐标归一 | `NormalizedBoundsTest` | 部分（旋转/分屏/键盘未真机验证） |
| E-04 | 敏感页面 | `SensitivePageDetector`（密码/FLAG_SECURE/支付/验证码） | `SensitivePageDetectorTest` | 部分（需真机敏感页验证） |
| E-05 | 停止 | 悬浮条/通知/撤权/锁屏/超时 | `GuidanceSessionStateTest` | 部分（锁屏与超时未真机验证） |
| E-06 | 伪造控制 | 拒绝 CLICK/SWIPE/INPUT/COMMIT_PAYMENT | `AssistMessageGuardTest`、`AssistSignalCoreTest`、`AssistScopesContractTest` | 已验证（自动化） |
| E-07 | 弱网 | WebRTC 自适应、标注过期 | 无 | 未验证（需弱网注入） |
| E-08 | 媒体不留存 | `AssistMediaPolicy`（帧不落盘） | `AssistMediaPolicyTest` | 部分（会话后存储/相册检查未执行） |
| X-01 | 云 AI 故障 | 超时/无效 JSON/配额降级 | `AiResultCacheServiceTest`、`QwenAiProviderTest`、`AiOutputSchemaContractTest` | 部分（真实 MaaS 故障未注入） |
| X-02 | TURN 故障 | TURN 凭证与失败提示 | 无 | 未验证（需 Coturn 环境） |
| X-03 | 数据库恢复 | `tests/ops/backup-restore-drill.mjs`（pg_dump -Fc → 空库 pg_restore → 逐表行数 + 整表 md5） | 本机演练 PASS：36 张表 / 2781 行 / 34 张非空表 md5 一致 | 部分（同实例新库；干净机器与异地介质未验证） |
| X-04 | 大字体/读屏 | 200% 字号、TalkBack、键盘 | Android 布局修复 + JVM 单测 | 部分（无 TalkBack 实测） |
| X-05 | 桌面安装 | Tauri 安装器、中文路径、WebView2 | 无 | 未验证 |

## 汇总（共 35 条）

- 已验证（自动化）：9 条 —— A-01、A-04、B-01、B-04、B-05、C-04、D-02、D-03、E-06。
- 部分：21 条 —— 单端或规则层已覆盖，注入/真机/三端联合未验证；X-03 已有同实例恢复演练。
- 未验证：5 条 —— A-05、A-06、E-07、X-02、X-05。
- 阻断发布的未验证项：E-07 弱网、X-02 TURN 故障（按 17.5 属 RC 前置条件）；X-03 需再补干净机器/异地介质演练。

## 待用户/环境提供

- 真机与厂商覆盖（A-05/A-06/E-03/E-04/E-05/X-04）：需要至少 2 台不同厂商 Android 设备。
- 真实 MaaS 凭证与配额（X-01、B-02）：用于 AI/OCR 真实链路验证。
- Coturn 服务器与双端网络（E-07、X-02）。
- 备份介质与恢复窗口（X-03）。
