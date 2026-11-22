# ActionLoop 日常生活行动系统

开发代号 ActionLoop。把通知、截图、文件和官方来源中的信息，转化为有原文依据、有明确步骤、可多人协同、可核验完成的统一事项，并提供本机或远程跨 App 高亮引导。

> 实施基线：`日常生活行动系统_完整开发指导书.md`（产品边界、领域模型、接口契约、安全要求、WP01—WP15、测试矩阵、验收清单）。赛事材料位于上级目录，只读核对，不修改。

## 环境（阶段 1 已验证可用）

| 组件 | 版本 | 说明 |
|---|---|---|
| JDK | 21（Temurin 21.0.12） | `JAVA_HOME` 指向 JDK 21 |
| Maven | 3.9.x / Wrapper 3.9.9 | 仓库内 `services/mvnw.cmd` |
| Node | 20+（实测 22.23.4） | 通过 nvm |
| pnpm | 10.15.0 | `corepack prepare pnpm@10.15.0 --activate` |
| Rust | stable | Tauri 桌面端构建 |
| PostgreSQL | 16+pgvector（compose）/ 本地 17 集群 | 见 ADR-001 |
| Redis | 7 | compose / 本地 |
| MinIO | 最新 | S3 兼容开发存储 |

## 快速启动

```powershell
# 1) 安装 Web/桌面依赖（仓库根目录）
corepack prepare pnpm@10.15.0 --activate
pnpm install

# 2) 启动基础设施（任选其一）
#    (a) Docker 路径：
docker compose --env-file .env.local -f infra/compose/docker-compose.local.yml up -d postgres redis minio
#    (b) 本机已有 PostgreSQL 时，可复用/自建集群，端口写入 .env.local（见 ADR-001）

# 3) 复制环境变量示例并填写
Copy-Item .env.local.example .env.local

# 4) 启动后端 API
.\services\mvnw.cmd -f services\pom.xml -pl api -am spring-boot:run
# 另一个终端启动 worker
.\services\mvnw.cmd -f services\pom.xml -pl worker -am spring-boot:run

# 5) 启动 Web（http://localhost:5173）
pnpm --filter @actionloop/web dev

# 6) 启动 Windows 桌面端（Tauri）
pnpm --filter @actionloop/desktop tauri dev

# 7) 构建 Android Debug
cd apps/android; .\gradlew.bat :app:assembleDebug
```

预期：`/actuator/health` 返回 UP（不暴露敏感详情）；Web 打开登录占位页；Tauri 启动桌面窗口（检测 WebView2）；Android APK 生成于 `apps/android/app/build/outputs/apk/debug/`。首次运行只创建合成种子（标注为演示数据），不自动抓取真实通知或文件。

## 质量门（PR 前）

```powershell
pnpm lint
pnpm typecheck
pnpm test
.\services\mvnw.cmd -f services\pom.xml verify
```

## 模块结构

见指导书第 10.3 节 Monorepo 目录。核心：`apps/`（android/web/desktop）、`services/`（api/worker/shared 模块化单体）、`contracts/`（OpenAPI/AsyncAPI/AI Schema）、`packages/`（生成客户端、设计 token）、`infra/`（编排）、`docs/adr/`（架构决策）。
