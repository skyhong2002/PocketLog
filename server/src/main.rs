#![forbid(unsafe_code)]
use pocketlog_server::{AppState, router};

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let token_file =
        std::env::var("POCKETLOG_TOKEN_FILE").unwrap_or_else(|_| "/run/secrets/owner_token".into());
    let token = std::fs::read_to_string(token_file).map_err(|_| "cannot read owner token file")?;
    let path =
        std::env::var("POCKETLOG_DATABASE").unwrap_or_else(|_| "/data/pocketlog.sqlite".into());
    let state = AppState::new(rusqlite::Connection::open(path)?, token.trim())?;
    state.prune().await?;
    let cleanup = state.clone();
    tokio::spawn(async move {
        let mut interval = tokio::time::interval(std::time::Duration::from_secs(3600));
        loop {
            interval.tick().await;
            if cleanup.prune().await.is_err() {
                eprintln!("retention cleanup failed");
            }
        }
    });
    // HTTP is for the local TLS proxy only. Compose binds the published port to loopback.
    let address = std::env::var("POCKETLOG_LISTEN").unwrap_or_else(|_| "127.0.0.1:8080".into());
    let listener = tokio::net::TcpListener::bind(address).await?;
    println!("PocketLog listening; request and content logging disabled");
    axum::serve(listener, router(state))
        .with_graceful_shutdown(async {
            let mut terminate =
                tokio::signal::unix::signal(tokio::signal::unix::SignalKind::terminate())
                    .expect("signal handler");
            tokio::select! { _ = tokio::signal::ctrl_c() => {}, _ = terminate.recv() => {} }
        })
        .await?;
    Ok(())
}
