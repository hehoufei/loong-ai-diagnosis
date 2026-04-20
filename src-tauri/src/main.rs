// Prevents additional console window on Windows in release
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

use std::process::{Child, Command};
use std::os::windows::process::CommandExt;
use std::sync::Mutex;
use tauri::Manager;

struct Backend(Mutex<Option<Child>>);

fn start_backend() -> Option<Child> {
    // 查找 resources 目录下的 jar 文件
    let exe_dir = std::env::current_exe()
        .ok()
        .and_then(|p| p.parent().map(|p| p.to_path_buf()))
        .unwrap_or_default();

    let jar_path = exe_dir.join("resources").join("loong-ai-diagnosis.jar");

    // 开发模式下从 target 目录找
    let jar = if jar_path.exists() {
        jar_path
    } else {
        // 开发模式: exe 在 src-tauri/target/debug/ 下，jar 在项目根目录/target/ 下
        let cwd = std::env::current_dir().unwrap_or_default();
        let candidates = vec![
            cwd.join("target").join("loong-ai-diagnosis.jar"),
            cwd.join("..").join("target").join("loong-ai-diagnosis.jar"),
            exe_dir.join("..").join("..").join("..").join("target").join("loong-ai-diagnosis.jar"),
        ];
        match candidates.iter().find(|p| p.exists()) {
            Some(p) => p.clone(),
            None => {
                eprintln!("找不到 jar 文件，尝试过: {:?}, {:?}", jar_path, candidates);
                return None;
            }
        }
    };

    println!("启动后端: {:?}", jar);

    match Command::new("javaw")
        .arg("-jar")
        .arg(&jar)
        .arg("--server.port=18080")
        .creation_flags(0x08000000) // CREATE_NO_WINDOW
        .spawn()
    {
        Ok(child) => {
            println!("后端已启动, PID: {}", child.id());
            Some(child)
        }
        Err(e) => {
            eprintln!("启动后端失败: {}", e);
            None
        }
    }
}

fn wait_for_backend(url: &str, timeout_secs: u64) -> bool {
    let start = std::time::Instant::now();
    let timeout = std::time::Duration::from_secs(timeout_secs);
    let client = std::time::Duration::from_millis(500);

    while start.elapsed() < timeout {
        if let Ok(resp) = ureq::get(url).timeout(client).call() {
            if resp.status() == 200 {
                return true;
            }
        }
        std::thread::sleep(std::time::Duration::from_millis(500));
    }
    false
}

fn main() {
    tauri::Builder::default()
        .setup(|app| {
            // 启动 Spring Boot 后端
            let child = start_backend();
            app.manage(Backend(Mutex::new(child)));

            // 等待后端就绪后再加载页面
            let window = app.get_webview_window("main").unwrap();
            std::thread::spawn(move || {
                if wait_for_backend("http://localhost:18080", 60) {
                    let _ = window.eval("window.location.replace('http://localhost:18080')");
                } else {
                    let _ = window.eval(
                        "document.body.innerHTML = '<h2 style=\"text-align:center;margin-top:200px;color:#cf1322\">后端启动失败，请检查 Java 环境</h2>'"
                    );
                }
            });

            Ok(())
        })
        .on_window_event(|window, event| {
            if let tauri::WindowEvent::Destroyed = event {
                // 关闭窗口时杀掉后端进程
                if let Some(state) = window.try_state::<Backend>() {
                    if let Ok(mut guard) = state.0.lock() {
                        if let Some(ref mut child) = *guard {
                            let _ = child.kill();
                            // 等待进程退出，确保文件句柄（如 VectorStore JSON）被 OS 释放
                            let _ = child.wait();
                            println!("后端进程已终止");
                        }
                    }
                }
            }
        })
        .run(tauri::generate_context!())
        .expect("启动应用失败");
}
