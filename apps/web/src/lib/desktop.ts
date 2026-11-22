// 桌面端（Tauri 2）能力桥接：动态导入 @tauri-apps/api，浏览器环境安全降级（不抛错、不假装可用）。
// 桌面能力仅限：只读诊断（WebView2 真实版本）、受控 UTF-8 文本文件读取（≤512KB）、拖放监听。
// 绝不提供远程控制、自动点击、输入注入或屏幕帧落盘。

let cachedTauri: boolean | null = null

export function isTauri(): boolean {
  if (cachedTauri === null) {
    cachedTauri =
      typeof window !== 'undefined' &&
      ('__TAURI_INTERNALS__' in window || '__TAURI__' in window)
  }
  return cachedTauri
}

export interface DesktopDiagnostics {
  platform: string
  appVersion: string
  webview2: string | null
}

export async function desktopDiagnostics(): Promise<DesktopDiagnostics | null> {
  if (!isTauri()) return null
  const { invoke } = await import('@tauri-apps/api/core')
  return invoke<DesktopDiagnostics>('diagnostics')
}

export interface DesktopTextFile {
  name: string
  path: string
  size: number
  content: string
}

/** 调用 Rust 端 read_text_file：只读、限 512KB、仅 UTF-8。失败返回错误信息，不静默降级。 */
export async function readDesktopTextFile(path: string): Promise<DesktopTextFile> {
  if (!isTauri()) throw new Error('仅桌面端支持读取本地文件')
  const { invoke } = await import('@tauri-apps/api/core')
  return invoke<DesktopTextFile>('read_text_file', { path })
}

export type FileDropHandler = (paths: string[]) => void | Promise<void>

/** 监听桌面窗口文件拖放，仅在 Tauri 环境生效；返回取消监听函数。 */
export async function listenFileDrop(handler: FileDropHandler): Promise<() => void> {
  if (!isTauri()) return () => {}
  const { getCurrentWebview } = await import('@tauri-apps/api/webview')
  const unlisten = await getCurrentWebview().onDragDropEvent((event) => {
    if (event.payload.type === 'drop') {
      const paths = (event.payload as { paths?: string[] }).paths ?? []
      if (paths.length > 0) void handler(paths)
    }
  })
  return unlisten
}
