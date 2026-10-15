//! Entrypoint for Vellune Background Worker jobs.

use tracing::info;

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    tracing_subscriber::fmt::init();
    info!("Starting Vellune Background Workers...");
    Ok(())
}
