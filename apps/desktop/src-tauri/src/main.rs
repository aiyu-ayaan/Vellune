// Prevents additional console window on Windows in release, DO NOT REMOVE!!
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

fn main() {
    // No native menu or title bar: the window is undecorated and the web UI draws its own title bar.
    tauri::Builder::default()
        .run(tauri::generate_context!())
        .expect("error while running Vellune");
}
