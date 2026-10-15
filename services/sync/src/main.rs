//! Entrypoint for Vellune WebSocket Real-time Sync Gateway.

use tracing::info;

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    tracing_subscriber::fmt::init();
    info!("Starting Vellune WebSocket Sync Gateway on 127.0.0.1:8081...");
    Ok(())
}
