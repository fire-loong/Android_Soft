// 桌面端入口：启动前检测 Microsoft Edge WebView2 Runtime（Windows），缺失则友好退出，不静默降级。
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

#[cfg(windows)]
fn ensure_webview2() {
    use winreg::enums::*;
    use winreg::RegKey;

    // 与 lib.rs::read_webview2_version 保持一致：同时检测 HKLM（全局安装）与 HKCU（按用户安装）。
    let candidates = [
        r"SOFTWARE\WOW6432Node\Microsoft\EdgeUpdate\Clients\{F3017226-FE2A-4295-8BDF-00C3A9A7E4C5}",
        r"SOFTWARE\Microsoft\EdgeUpdate\Clients\{F3017226-FE2A-4295-8BDF-00C3A9A7E4C5}",
    ];
    let installed = [HKEY_LOCAL_MACHINE, HKEY_CURRENT_USER].iter().any(|hive| {
        let root = RegKey::predef(*hive);
        candidates.iter().any(|path| {
            root.open_subkey(path)
                .map(|key| key.get_value::<String, _>("pv").is_ok())
                .unwrap_or(false)
        })
    });
    if !installed {
        eprintln!(
            "[ActionLoop] 缺少 Microsoft Edge WebView2 Runtime，请安装：https://developer.microsoft.com/microsoft-edge/webview2/"
        );
        std::process::exit(1);
    }
}

#[cfg(not(windows))]
fn ensure_webview2() {}

fn main() {
    ensure_webview2();
    actionloop_desktop_lib::run();
}
