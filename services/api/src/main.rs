//! Entrypoint for Vellune HTTP API service.

use tracing::info;

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    tracing_subscriber::fmt::init();
    info!("Starting Vellune HTTP API service on 127.0.0.1:8080...");
    Ok(())
}
