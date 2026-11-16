//! Same-origin browser sessions. Owner credentials never enter URLs or browser storage.
use super::*;
use axum::{extract::Request, response::Html};

pub(super) struct Session {
    digest: [u8; 32],
    expires: i64,
}
const COOKIE: &str = "__Host-pocketlog";
fn cookie(req: &Request) -> Option<&str> {
    req.headers()
        .get(header::COOKIE)?
        .to_str()
        .ok()?
        .split(';')
        .filter_map(|s| s.trim().split_once('='))
        .find_map(|(k, v)| (k == COOKIE).then_some(v))
}
pub(super) fn authorized(state: &AppState, req: &Request) -> bool {
    // A non-simple header prevents cross-origin forms and fetches from mutating journals.
    if req.method() != axum::http::Method::GET
        && req
            .headers()
            .get("x-pocketlog")
            .and_then(|v| v.to_str().ok())
            != Some("1")
    {
        return false;
    }
    if req
        .headers()
        .get("sec-fetch-site")
        .is_some_and(|v| v == "cross-site")
    {
        return false;
    }
    let Some(value) = cookie(req) else {
        return false;
    };
    let hash: [u8; 32] = Sha256::digest(value.as_bytes()).into();
    state.sessions.lock().is_ok_and(|mut sessions| {
        sessions.retain(|s| s.expires > now_ms());
        sessions.iter().any(|s| bool::from(s.digest.ct_eq(&hash)))
    })
}
pub(super) fn routes() -> Router<AppState> {
    Router::new()
        .route(
            "/",
            get(|| async { Html(include_str!("../web/index.html")) }),
        )
        .route(
            "/app.js",
            get(|| async {
                (
                    [(header::CONTENT_TYPE, "text/javascript; charset=utf-8")],
                    include_str!("../web/app.js"),
                )
            }),
        )
        .route(
            "/app.css",
            get(|| async {
                (
                    [(header::CONTENT_TYPE, "text/css; charset=utf-8")],
                    include_str!("../web/app.css"),
                )
            }),
        )
        .route(
            "/logo.png",
            get(|| async {
                (
                    [(header::CONTENT_TYPE, "image/png")],
                    include_bytes!("../web/logo.png").as_slice(),
                )
            }),
        )
        .route(
            "/fonts/notosans.woff2",
            get(|| async {
                (
                    [(header::CONTENT_TYPE, "font/woff2")],
                    include_bytes!("../web/fonts/notosans.woff2").as_slice(),
                )
            }),
        )
        .route(
            "/fonts/notosanstc.woff2",
            get(|| async {
                (
                    [(header::CONTENT_TYPE, "font/woff2")],
                    include_bytes!("../web/fonts/notosanstc.woff2").as_slice(),
                )
            }),
        )
        .route(
            "/fonts/notosans-OFL.txt",
            get(|| async {
                (
                    [(header::CONTENT_TYPE, "text/plain; charset=utf-8")],
                    include_bytes!("../web/fonts/notosans-OFL.txt").as_slice(),
                )
            }),
        )
        .route(
            "/fonts/notosanstc-OFL.txt",
            get(|| async {
                (
                    [(header::CONTENT_TYPE, "text/plain; charset=utf-8")],
                    include_bytes!("../web/fonts/notosanstc-OFL.txt").as_slice(),
                )
            }),
        )
        .route("/session", post(login).delete(logout))
}
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Login {
    token: String,
}
async fn login(
    State(state): State<AppState>,
    headers: axum::http::HeaderMap,
    Json(input): Json<Login>,
) -> Result<Response> {
    if headers.get("x-pocketlog").and_then(|v| v.to_str().ok()) != Some("1")
        || headers
            .get("sec-fetch-site")
            .is_some_and(|v| v == "cross-site")
    {
        return Err(ApiError(
            StatusCode::FORBIDDEN,
            "same-origin request required",
        ));
    }
    let hash: [u8; 32] = Sha256::digest(input.token.as_bytes()).into();
    if !bool::from(state.token_hash.ct_eq(&hash)) {
        return Err(ApiError(StatusCode::UNAUTHORIZED, "unauthorized"));
    }
    let mut random = [0u8; 32];
    getrandom::fill(&mut random).map_err(storage_error)?;
    let value = crate::hex_digest(random);
    let mut sessions = state.sessions.lock().map_err(storage_error)?;
    sessions.retain(|s| s.expires > now_ms());
    // Bound RAM; existing devices are never silently evicted by a new login.
    if sessions.len() >= 32 {
        return Err(ApiError(
            StatusCode::TOO_MANY_REQUESTS,
            "session capacity reached",
        ));
    }
    sessions.push(Session {
        digest: Sha256::digest(value.as_bytes()).into(),
        expires: now_ms() + 8 * 3_600_000,
    });
    let mut response = StatusCode::NO_CONTENT.into_response();
    response.headers_mut().insert(
        header::SET_COOKIE,
        format!("{COOKIE}={value}; Path=/; Secure; HttpOnly; SameSite=Strict; Max-Age=28800")
            .parse()
            .map_err(storage_error)?,
    );
    Ok(response)
}
async fn logout(State(state): State<AppState>, req: Request) -> Result<Response> {
    if !authorized(&state, &req) {
        return Err(ApiError(StatusCode::UNAUTHORIZED, "unauthorized"));
    }
    if let Some(value) = cookie(&req) {
        let hash: [u8; 32] = Sha256::digest(value.as_bytes()).into();
        state
            .sessions
            .lock()
            .map_err(storage_error)?
            .retain(|s| !bool::from(s.digest.ct_eq(&hash)));
    }
    let mut response = StatusCode::NO_CONTENT.into_response();
    response.headers_mut().insert(
        header::SET_COOKIE,
        format!("{COOKIE}=; Path=/; Secure; HttpOnly; SameSite=Strict; Max-Age=0")
            .parse()
            .map_err(storage_error)?,
    );
    Ok(response)
}
