//! Owner-managed provider credentials. Only redacted settings can be read over the API.
use super::*;
use std::{
    fs,
    io::Read,
    os::unix::fs::{OpenOptionsExt, PermissionsExt},
    path::{Path, PathBuf},
};

#[derive(Clone)]
pub struct AiSettingsStore {
    path: PathBuf,
}
#[derive(Clone, Deserialize, Serialize)]
#[serde(deny_unknown_fields)]
pub(crate) struct StoredSettings {
    #[serde(default)]
    pub protocol: Protocol,
    pub enabled: bool,
    pub endpoint: String,
    pub model: String,
    pub key: String,
    pub provider_id: String,
    pub image_support: bool,
    pub response_format: OutputFormat,
}
#[derive(Clone, Copy, Debug, PartialEq, Eq, Deserialize, Serialize, Default)]
#[serde(rename_all = "snake_case")]
pub(crate) enum Protocol {
    #[default]
    Openai,
    Anthropic,
    Gemini,
}
#[derive(Clone, Copy, Deserialize, Serialize, Default)]
#[serde(rename_all = "snake_case")]
pub(crate) enum OutputFormat {
    #[default]
    JsonSchema,
    JsonObject,
}
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Update {
    #[serde(default)]
    protocol: Protocol,
    endpoint: String,
    model: String,
    // Missing retains a key only if the endpoint is identical. Empty explicitly clears it.
    api_key: Option<String>,
    #[serde(default)]
    image_support: bool,
    #[serde(default)]
    response_format: OutputFormat,
}
impl AiSettingsStore {
    pub(crate) fn reader(directory: &Path) -> Self {
        Self {
            path: directory.join("provider.json"),
        }
    }
    pub fn new(directory: &Path) -> std::result::Result<Self, &'static str> {
        fs::create_dir_all(directory).map_err(|_| "cannot create AI settings directory")?;
        let metadata =
            fs::symlink_metadata(directory).map_err(|_| "cannot inspect AI settings directory")?;
        if !metadata.is_dir() || metadata.file_type().is_symlink() {
            return Err("invalid AI settings directory");
        }
        fs::set_permissions(directory, fs::Permissions::from_mode(0o700))
            .map_err(|_| "cannot protect AI settings directory")?;
        Ok(Self {
            path: directory.join("provider.json"),
        })
    }
    pub(crate) fn load(&self) -> std::result::Result<Option<StoredSettings>, &'static str> {
        let file = match fs::File::open(&self.path) {
            Ok(file) => file,
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => return Ok(None),
            Err(_) => return Err("cannot read AI settings"),
        };
        let mut bytes = Vec::new();
        file.take(16 * 1024 + 1)
            .read_to_end(&mut bytes)
            .map_err(|_| "cannot read AI settings")?;
        if bytes.len() > 16 * 1024 {
            return Err("invalid AI settings");
        }
        serde_json::from_slice(&bytes)
            .map(Some)
            .map_err(|_| "invalid AI settings")
    }
    fn save(&self, settings: &StoredSettings) -> Result<()> {
        use std::io::Write;
        let temporary = self.path.with_extension("pending");
        let mut file = fs::OpenOptions::new()
            .write(true)
            .create(true)
            .truncate(true)
            .mode(0o600)
            .open(&temporary)
            .map_err(storage_error)?;
        fs::set_permissions(&temporary, fs::Permissions::from_mode(0o600))
            .map_err(storage_error)?;
        let bytes = serde_json::to_vec(settings).map_err(storage_error)?;
        file.write_all(&bytes).map_err(storage_error)?;
        file.sync_all().map_err(storage_error)?;
        fs::rename(&temporary, &self.path).map_err(storage_error)?;
        fs::File::open(self.path.parent().ok_or_else(invalid)?)
            .and_then(|d| d.sync_all())
            .map_err(storage_error)?;
        Ok(())
    }
    pub(crate) fn matches(&self, id: &str) -> bool {
        self.load()
            .ok()
            .flatten()
            .is_some_and(|s| s.enabled && s.provider_id == id)
    }
}
fn redacted(value: Option<&StoredSettings>) -> serde_json::Value {
    match value {
        Some(s) => {
            serde_json::json!({"available":true,"probe_provider_binding":true,"protocol":s.protocol,"enabled":s.enabled,"endpoint":s.endpoint,"model":s.model,"provider_id":s.provider_id,"has_key":!s.key.is_empty(),"image_support":s.image_support,"response_format":s.response_format})
        }
        None => {
            serde_json::json!({"available":true,"probe_provider_binding":true,"enabled":false,"has_key":false})
        }
    }
}
pub(super) fn routes() -> Router<AppState> {
    Router::new()
        .route("/v1/ai", get(read).put(update).delete(clear))
        .route("/v1/ai/test", post(probe))
}
async fn read(State(state): State<AppState>) -> Result<Json<serde_json::Value>> {
    let Some(store) = state.ai_settings.clone() else {
        return Ok(Json(
            serde_json::json!({"available":false,"probe_provider_binding":true,"enabled":false}),
        ));
    };
    state
        .database(move |_| {
            let value = store.load().map_err(storage_error)?;
            Ok(Json(redacted(value.as_ref())))
        })
        .await
}
async fn update(
    State(state): State<AppState>,
    Json(change): Json<Update>,
) -> Result<Json<serde_json::Value>> {
    let store = state.ai_settings.clone().ok_or(ApiError(
        StatusCode::SERVICE_UNAVAILABLE,
        "AI settings unavailable",
    ))?;
    state
        .database(move |db| {
            let previous = store.load().map_err(storage_error)?;
            let key = match change.api_key {
                Some(key) => key,
                None => previous
                    .filter(|s| {
                        s.enabled && s.endpoint == change.endpoint && s.protocol == change.protocol
                    })
                    .map(|s| s.key)
                    .unwrap_or_default(),
            };
            analysis::validate_configuration(&change.endpoint, &change.model, &key)
                .map_err(|_| invalid())?;
            analysis::validate_protocol(change.protocol, &change.endpoint, &change.model)
                .map_err(|_| invalid())?;
            let mut nonce = [0u8; 32];
            getrandom::fill(&mut nonce).map_err(storage_error)?;
            let id = format!("{:x}", Sha256::digest(nonce));
            let settings = StoredSettings {
                protocol: change.protocol,
                enabled: true,
                endpoint: change.endpoint,
                model: change.model,
                key,
                provider_id: id,
                image_support: change.image_support,
                response_format: change.response_format,
            };
            let tx = db.transaction().map_err(storage_error)?;
            tx.execute("UPDATE journal_meta SET epoch=epoch+1", [])
                .map_err(storage_error)?;
            tx.execute("DELETE FROM analysis_jobs", [])
                .map_err(storage_error)?;
            store.save(&settings)?;
            tx.commit().map_err(storage_error)?;
            Ok(Json(redacted(Some(&settings))))
        })
        .await
}
async fn clear(State(state): State<AppState>) -> Result<StatusCode> {
    let store = state.ai_settings.clone().ok_or(ApiError(
        StatusCode::SERVICE_UNAVAILABLE,
        "AI settings unavailable",
    ))?;
    state
        .database(move |db| {
            let settings = StoredSettings {
                protocol: Protocol::Openai,
                enabled: false,
                endpoint: String::new(),
                model: String::new(),
                key: String::new(),
                provider_id: String::new(),
                image_support: false,
                response_format: OutputFormat::JsonSchema,
            };
            let tx = db.transaction().map_err(storage_error)?;
            tx.execute("UPDATE journal_meta SET epoch=epoch+1", [])
                .map_err(storage_error)?;
            tx.execute("DELETE FROM analysis_jobs", [])
                .map_err(storage_error)?;
            store.save(&settings)?;
            tx.commit().map_err(storage_error)?;
            Ok(())
        })
        .await?;
    Ok(StatusCode::NO_CONTENT)
}
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct ProbeRequest {
    provider_id: String,
}
async fn probe(
    State(state): State<AppState>,
    request: std::result::Result<Json<ProbeRequest>, axum::extract::rejection::JsonRejection>,
) -> Result<Json<serde_json::Value>> {
    let Json(request) = request.map_err(|_| invalid())?;
    if request.provider_id.len() != 64
        || !request.provider_id.bytes().all(|b| b.is_ascii_hexdigit())
    {
        return Err(invalid());
    }
    let store = state.ai_settings.clone().ok_or(ApiError(
        StatusCode::SERVICE_UNAVAILABLE,
        "AI settings unavailable",
    ))?;
    let permit = state
        .ai_probe_slot
        .clone()
        .try_acquire_owned()
        .map_err(|_| ApiError(StatusCode::TOO_MANY_REQUESTS, "test already running"))?;
    tokio::task::spawn_blocking(move || {
        let _permit = permit;
        let provider = analysis::Provider::from_store(&store)
            .map_err(storage_error)?
            .ok_or(ApiError(StatusCode::CONFLICT, "provider changed"))?;
        if provider.identity() != Some(request.provider_id.as_str()) {
            return Err(ApiError(StatusCode::CONFLICT, "provider changed"));
        }
        provider
            .probe()
            .map_err(|_| ApiError(StatusCode::BAD_GATEWAY, "model connection test failed"))?;
        Ok(Json(
            serde_json::json!({"ok":true,"journal_data_sent":false}),
        ))
    })
    .await
    .map_err(storage_error)?
}
