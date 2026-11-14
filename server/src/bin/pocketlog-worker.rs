#![forbid(unsafe_code)]
fn main() -> Result<(), &'static str> {
    let path =
        std::env::var("POCKETLOG_DATABASE").unwrap_or_else(|_| "/data/pocketlog.sqlite".into());
    let mut db = rusqlite::Connection::open(path).map_err(|_| "cannot open journal")?;
    db.busy_timeout(std::time::Duration::from_secs(2))
        .map_err(|_| "cannot configure journal")?;
    db.execute_batch("PRAGMA secure_delete=ON;")
        .map_err(|_| "cannot configure journal")?;
    let config_directory = std::env::var("POCKETLOG_AI_CONFIG_DIR").ok();
    loop {
        let provider = match &config_directory {
            Some(directory) => {
                pocketlog_server::Provider::from_config_directory(std::path::Path::new(directory))
            }
            None => pocketlog_server::Provider::from_env(),
        };
        // Invalid or temporarily unavailable configuration fails closed: never send or consume jobs.
        let provider = match provider {
            Ok(provider) => provider,
            Err(_) => {
                eprintln!("AI settings unavailable");
                std::thread::sleep(std::time::Duration::from_secs(60));
                continue;
            }
        };
        if pocketlog_server::process_with_provider(&mut db, provider.as_ref()).is_err() {
            eprintln!("context processing failed");
        }
        std::thread::sleep(std::time::Duration::from_secs(60));
    }
}
