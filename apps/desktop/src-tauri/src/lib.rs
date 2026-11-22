// Tauri 2 应用库：复用 Web 前端构建产物（frontendDist 指向 apps/web/dist）。
// 桌面能力仅限：只读诊断、受控文本文件读取（用于导入来源）、拖放。绝不执行远程控制。
//
// 安全边界（对照指导书 §10.1 / §15.4）：
// - read_text_file 仅允许扩展白名单内的文本文件，拒绝符号链接/设备/UNC，限 512KB 且必须为 UTF-8；
//   调用路径只能来自用户拖放，前端 CSP 锁定 script-src 'self'，注入脚本无法调用本命令。
// - 不注册 shell 插件、不开放任意命令执行、不捕获屏幕、不落盘原始帧。
use serde::Serialize;
use std::path::{Path, PathBuf};

const MAX_TEXT_BYTES: u64 = 512 * 1024;

/// 允许导入为“来源”原文证据的文本扩展名（白名单，阻止任意文件读取）。
const ALLOWED_EXTENSIONS: &[&str] = &[
    "txt", "md", "markdown", "csv", "json", "log", "xml", "yml", "yaml", "ini", "conf", "toml", "srt", "vtt",
];

#[derive(Serialize)]
struct Diagnostics {
    platform: String,
    app_version: String,
    webview2: Option<String>,
}

#[derive(Serialize)]
struct TextFile {
    name: String,
    path: String,
    size: u64,
    content: String,
}

#[cfg(windows)]
fn read_webview2_version() -> Option<String> {
    use winreg::enums::*;
    use winreg::RegKey;

    for hive in [HKEY_LOCAL_MACHINE, HKEY_CURRENT_USER] {
        let root = RegKey::predef(hive);
        for path in [
            r"SOFTWARE\WOW6432Node\Microsoft\EdgeUpdate\Clients\{F3017226-FE2A-4295-8BDF-00C3A9A7E4C5}",
            r"SOFTWARE\Microsoft\EdgeUpdate\Clients\{F3017226-FE2A-4295-8BDF-00C3A9A7E4C5}",
        ] {
            if let Ok(key) = root.open_subkey(path) {
                if let Ok(version) = key.get_value::<String, _>("pv") {
                    if !version.trim().is_empty() {
                        return Some(version);
                    }
                }
            }
        }
    }
    None
}

#[cfg(not(windows))]
fn read_webview2_version() -> Option<String> {
    None
}

/// 桌面环境诊断：WebView2 版本必须真实读取，缺失时前端明确提示而不是假装可用。
#[tauri::command]
fn diagnostics() -> Diagnostics {
    Diagnostics {
        platform: std::env::consts::OS.to_string(),
        app_version: env!("CARGO_PKG_VERSION").to_string(),
        webview2: read_webview2_version(),
    }
}

fn is_allowed_text_file(path: &Path) -> bool {
    path.extension()
        .and_then(|e| e.to_str())
        .map(|e| ALLOWED_EXTENSIONS.contains(&e.to_ascii_lowercase().as_str()))
        .unwrap_or(false)
}

/// 只读导入 UTF-8 文本文件：限 512KB、扩展白名单、拒绝符号链接与 UNC，用于“来源”原文证据。
/// 绝对路径只回传给当前 webview 本地展示，前端不会把它上传到服务端（见 DesktopTools）。
#[tauri::command]
fn read_text_file(path: String) -> Result<TextFile, String> {
    let raw = PathBuf::from(&path);
    // 拒绝符号链接（防止逃逸出用户拖放范围）。
    let link_meta = std::fs::symlink_metadata(&raw).map_err(|e| format!("无法读取文件：{e}"))?;
    if link_meta.file_type().is_symlink() {
        return Err("不支持符号链接文件".into());
    }
    if !link_meta.is_file() {
        return Err("只支持单个文件".into());
    }
    let canonical = std::fs::canonicalize(&raw).map_err(|e| format!("无法解析路径：{e}"))?;
    // 拒绝 UNC / 网络共享与设备路径。
    let canonical_str = canonical.to_string_lossy();
    if canonical_str.starts_with("\\\\") || canonical_str.starts_with("//") {
        return Err("不支持网络或设备路径".into());
    }
    if !is_allowed_text_file(&canonical) {
        return Err(format!(
            "仅支持文本类型（{}），请改用“选择文件”按钮或重命名扩展名",
            ALLOWED_EXTENSIONS.join("/")
        ));
    }
    let meta = std::fs::metadata(&canonical).map_err(|e| format!("读取失败：{e}"))?;
    if meta.len() > MAX_TEXT_BYTES {
        return Err("文件超过 512KB，请先在本地截取关键段落".into());
    }
    let bytes = std::fs::read(&canonical).map_err(|e| format!("读取失败：{e}"))?;
    let content = String::from_utf8(bytes).map_err(|_| "仅支持 UTF-8 文本文件".to_string())?;
    let name = canonical
        .file_name()
        .map(|n| n.to_string_lossy().to_string())
        .unwrap_or_else(|| "未命名".to_string());
    Ok(TextFile {
        name,
        path: canonical.to_string_lossy().to_string(),
        size: meta.len(),
        content,
    })
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .invoke_handler(tauri::generate_handler![diagnostics, read_text_file])
        .run(tauri::generate_context!())
        .expect("error while running ActionLoop desktop");
}
