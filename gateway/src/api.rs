mod sessions;
mod stream;
use crate::{
    events::Bus,
    model::Host,
    protocol::{GATEWAY_PROTOCOL_VERSION, OperationDto, ServerEvent, SessionDto, SessionSnapshot},
    runtime::SessionDirectory,
    storage::Store,
    uploads,
    workspace::Browser,
};
use axum::{
    Json, Router,
    extract::{DefaultBodyLimit, Path, Query, State},
    http::{HeaderMap, StatusCode},
    middleware::{self, Next},
    response::{IntoResponse, Response},
    routing::{any, get, post, put},
};
use serde::Deserialize;
use serde_json::{Value, json};
use sessions::{
    api_command, api_commands, api_create, api_delete, api_detail, api_models, api_operation,
    api_sessions, api_upload,
};
use std::{path::Path as FsPath, sync::Arc};
use stream::api_stream;
use tower_http::compression::CompressionLayer;

#[derive(Clone)]
pub struct App {
    pub host: Host,
    pub store: Arc<Store>,
    pub browser: Arc<Browser>,
    pub bus: Arc<Bus>,
    pub sessions: Arc<SessionDirectory>,
}
pub fn router(app: App) -> Router {
    let usage = Arc::new(crate::usage::UsageService::new(
        app.sessions.omp_executable().to_owned(),
        app.sessions.omp_args(),
    ));
    let protected = Router::new()
        .route("/api/v4/host", get(api_host))
        .route("/api/v4/usage", get(api_usage))
        .layer(axum::Extension(usage))
        .route("/api/v4/workspaces", get(workspaces))
        .route("/api/v4/fs/list", get(list))
        .route("/api/v4/sessions", get(api_sessions).post(api_create))
        .route("/api/v4/sessions/{id}", get(api_detail).delete(api_delete))
        .route(
            "/api/v4/sessions/{id}/commands",
            get(api_commands).post(api_command),
        )
        .route(
            "/api/v4/sessions/{id}/files/{file_id}",
            put(api_upload).layer(DefaultBodyLimit::max(uploads::MAX_FILE_BYTES)),
        )
        .route(
            "/api/v4/sessions/{id}/operations/{command_id}",
            get(api_operation),
        )
        .route("/api/v4/sessions/{id}/models", get(api_models))
        .route("/api/v4/sessions/{id}/history", get(api_history))
        .route("/api/v4/sessions/{id}/history/sync", post(api_history_sync))
        .route(
            "/api/v4/sessions/{id}/tools/{call_id}",
            get(api_tool_detail),
        )
        .route("/api/v4/events", get(api_stream))
        .route_layer(middleware::from_fn_with_state(app.clone(), authenticate));
    Router::new()
        .route("/health", get(|| async { "pinkcollab:ok" }))
        .route("/api/v4/pair", post(api_pair))
        .route("/api/v1/{*path}", any(upgrade_required))
        .route("/api/v2/{*path}", any(upgrade_required))
        .route("/api/v3/{*path}", any(upgrade_required))
        .merge(protected)
        .layer(DefaultBodyLimit::max(512 * 1024))
        .layer(middleware::from_fn(server_timing))
        .layer(middleware::from_fn(security_headers))
        .layer(CompressionLayer::new())
        .with_state(app)
}

struct ApiError(StatusCode, &'static str, String);
impl IntoResponse for ApiError {
    fn into_response(self) -> Response {
        (self.0, Json(json!({"code":self.1,"message":self.2}))).into_response()
    }
}
async fn api_client(app: &App, headers: &HeaderMap) -> Result<String, ApiError> {
    let token = credential(headers).unwrap_or_default().to_owned();
    let client = app
        .store
        .run(move |store| Ok(store.client_id(&token)))
        .await
        .ok()
        .flatten();
    client.ok_or_else(|| {
        ApiError(
            StatusCode::UNAUTHORIZED,
            "authentication_required",
            "Paired client credential required".into(),
        )
    })
}
async fn api_pair(
    State(app): State<App>,
    Json(body): Json<Pair>,
) -> Result<(StatusCode, Json<Value>), ApiError> {
    if body.token.len() != 48 || body.name.trim().is_empty() || body.name.len() > 100 {
        return Err(ApiError(
            StatusCode::BAD_REQUEST,
            "invalid_request",
            "Token and client name required".into(),
        ));
    }
    let (client_id, credential) = app
        .store
        .run(move |store| store.pair(&body.token, &body.name))
        .await
        .map_err(|_| {
            ApiError(
                StatusCode::UNAUTHORIZED,
                "invalid_pairing_token",
                "Invalid or expired pairing token".into(),
            )
        })?;
    Ok((
        StatusCode::CREATED,
        Json(
            json!({"clientId":client_id,"credential":credential,"host":app.host,"protocolVersion":GATEWAY_PROTOCOL_VERSION}),
        ),
    ))
}
async fn api_host(State(app): State<App>) -> Json<Value> {
    Json(
        json!({"host":app.host,"protocolVersion":GATEWAY_PROTOCOL_VERSION,"capabilities":{"ompRpcTransport":1,"modelSelection":true,"thinkingLevels":true,"historyPaging":true,"resume":true,"fileUploads":true}}),
    )
}
#[derive(Deserialize)]
struct ApiHistoryQuery {
    cursor: Option<String>,
    limit: Option<usize>,
    anchor: Option<String>,
    oldest: Option<String>,
}
async fn api_history(
    State(app): State<App>,
    Path(id): Path<String>,
    Query(query): Query<ApiHistoryQuery>,
) -> Result<Json<Value>, ApiError> {
    let mut page = read_history(&app, &id, &query).await?;
    crate::wire::share_todos(&mut page, &mut Default::default());
    Ok(Json(page))
}

async fn api_history_sync(
    State(app): State<App>,
    Path(id): Path<String>,
    Json(request): Json<crate::history::HistorySync>,
) -> Result<Json<Value>, ApiError> {
    let view = history_view(&app, &id, || {
        ApiError(
            StatusCode::SERVICE_UNAVAILABLE,
            "history_unavailable",
            "History unavailable".into(),
        )
    })
    .await?;
    let Some(path) = view.session.engine_session_ref else {
        return Ok(Json(json!({"items":[],"source":null,"nextCursor":null})));
    };
    app.sessions
        .history_sync(&id, &path, &request)
        .await
        .map(|mut page| {
            crate::wire::share_todos(&mut page, &mut Default::default());
            Json(page)
        })
        .map_err(|err| {
            let code = if err.to_string().contains("stale_cursor") {
                "stale_cursor"
            } else if err.to_string().contains("invalid_sync") {
                "invalid_request"
            } else {
                "history_unavailable"
            };
            ApiError(
                if code == "stale_cursor" {
                    StatusCode::CONFLICT
                } else if code == "invalid_request" {
                    StatusCode::BAD_REQUEST
                } else {
                    StatusCode::SERVICE_UNAVAILABLE
                },
                code,
                "History synchronization unavailable".into(),
            )
        })
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct ToolDetailQuery {
    cursor: Option<String>,
}

async fn api_tool_detail(
    State(app): State<App>,
    Path((id, call_id)): Path<(String, String)>,
    Query(query): Query<ToolDetailQuery>,
) -> Result<Json<Value>, ApiError> {
    let unavailable = || {
        ApiError(
            StatusCode::SERVICE_UNAVAILABLE,
            "tool_detail_unavailable",
            "Tool details are temporarily unavailable".into(),
        )
    };
    let view = history_view(&app, &id, unavailable).await?;
    let tool = match app.sessions.live_tool(&id, &call_id).await {
        Some(tool) => Some(tool),
        None => match view.session.engine_session_ref {
            Some(reference) => crate::history::tool_detail(FsPath::new(&reference), &call_id)
                .await
                .map_err(|_| unavailable())?,
            None => None,
        },
    }
    .ok_or_else(|| {
        ApiError(
            StatusCode::NOT_FOUND,
            "tool_not_found",
            "Tool is not present in this conversation branch".into(),
        )
    })?;
    crate::tool_details::detail_page(&tool, query.cursor.as_deref())
        .map(Json)
        .map_err(|error| {
            if error.to_string() == "stale_detail" {
                ApiError(
                    StatusCode::CONFLICT,
                    "stale_detail",
                    "Tool details changed; reload the first page".into(),
                )
            } else {
                ApiError(
                    StatusCode::BAD_REQUEST,
                    "invalid_detail_cursor",
                    "Invalid tool detail cursor".into(),
                )
            }
        })
}

async fn history_view(
    app: &App,
    id: &str,
    unavailable: impl FnOnce() -> ApiError,
) -> Result<crate::domain::SessionView, ApiError> {
    app.sessions
        .history_view(id)
        .await
        .map_err(|_| unavailable())?
        .ok_or_else(|| {
            ApiError(
                StatusCode::NOT_FOUND,
                "session_not_found",
                "Session not found".into(),
            )
        })
}

async fn read_history(app: &App, id: &str, query: &ApiHistoryQuery) -> Result<Value, ApiError> {
    let session = history_view(app, id, || {
        ApiError(
            StatusCode::SERVICE_UNAVAILABLE,
            "history_unavailable",
            "OMP history is unavailable".into(),
        )
    })
    .await?;
    let Some(reference) = session.session.engine_session_ref else {
        return Ok(json!({"items":[],"source":null,"nextCursor":null}));
    };
    let page = app
        .sessions
        .history_page(
            id,
            &reference,
            query.cursor.as_deref(),
            query.limit.unwrap_or(50),
            query.anchor.as_deref(),
            query.oldest.as_deref(),
        )
        .await
        .map_err(|err| {
            let raw = format!("{err:#}");
            if raw.contains("stale_cursor") {
                ApiError(
                    StatusCode::CONFLICT,
                    "stale_cursor",
                    "History changed; reload from the first page".into(),
                )
            } else if raw.contains("invalid_page_limit") {
                ApiError(
                    StatusCode::BAD_REQUEST,
                    "invalid_page_limit",
                    "Limit must be 1..100".into(),
                )
            } else {
                ApiError(
                    StatusCode::SERVICE_UNAVAILABLE,
                    "history_unavailable",
                    "OMP history is unavailable".into(),
                )
            }
        })?;
    Ok(page)
}

fn credential(headers: &HeaderMap) -> Option<&str> {
    headers
        .get("authorization")?
        .to_str()
        .ok()?
        .strip_prefix("Bearer ")
}
async fn authenticate(
    State(app): State<App>,
    request: axum::extract::Request,
    next: Next,
) -> Response {
    let token = credential(request.headers()).unwrap_or_default().to_owned();
    if !app
        .store
        .run(move |store| Ok(store.authenticate(&token)))
        .await
        .unwrap_or(false)
    {
        return ApiError(
            StatusCode::UNAUTHORIZED,
            "authentication_required",
            "paired client credential required".into(),
        )
        .into_response();
    }
    next.run(request).await
}
async fn security_headers(request: axum::extract::Request, next: Next) -> Response {
    if request.headers().contains_key("origin") {
        return ApiError(
            StatusCode::FORBIDDEN,
            "browser_origin_forbidden",
            "browser origins are not supported".into(),
        )
        .into_response();
    }
    let mut response = next.run(request).await;
    response
        .headers_mut()
        .insert("cache-control", "no-store".parse().unwrap());
    response
        .headers_mut()
        .insert("x-content-type-options", "nosniff".parse().unwrap());
    response
}
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Pair {
    token: String,
    name: String,
}
async fn upgrade_required() -> Response {
    ApiError(
        StatusCode::UPGRADE_REQUIRED,
        "protocol_upgrade_required",
        "This Gateway API version is no longer supported; update PinkCollab".into(),
    )
    .into_response()
}
async fn workspaces(State(app): State<App>) -> Json<Value> {
    Json(json!({"workspaces":app.browser.roots()}))
}
#[derive(Deserialize)]
struct ListQuery {
    path: String,
}
async fn list(
    State(app): State<App>,
    Query(query): Query<ListQuery>,
) -> Result<Json<Value>, ApiError> {
    let listing = app
        .browser
        .list(FsPath::new(&query.path))
        .await
        .map_err(|_| {
            ApiError(
                StatusCode::FORBIDDEN,
                "workspace_forbidden",
                "Directory is outside the allowed roots or unavailable".into(),
            )
        })?;
    Ok(Json(json!(listing)))
}
async fn server_timing(request: axum::extract::Request, next: Next) -> Response {
    let started = std::time::Instant::now();
    let mut response = next.run(request).await;
    if let Ok(value) = format!(
        "gateway;dur={:.3}",
        started.elapsed().as_secs_f64() * 1000.0
    )
    .parse()
    {
        response.headers_mut().insert("server-timing", value);
    }
    response
}

// The fence is server-local. A snapshot supersedes queued events up to this sequence.
async fn snapshot(app: &App, session_id: Option<&str>) -> Option<(u64, ServerEvent)> {
    capture_snapshot(app, session_id, None).await
}
async fn capture_snapshot(
    app: &App,
    session_id: Option<&str>,
    focus: Option<&str>,
) -> Option<(u64, ServerEvent)> {
    let started = std::time::Instant::now();
    for _ in 0..5 {
        let before = app.bus.sequence(session_id);
        let payload = if let Some(id) = session_id {
            ServerEvent::SessionSnapshot {
                session_id: id.into(),
                snapshot: app.sessions.view(id).await.ok()??.dto(),
            }
        } else {
            let (sessions, next_sessions_cursor, catalog) =
                app.sessions.bootstrap(focus).await.ok()?;
            ServerEvent::HostSnapshot {
                total_sessions: catalog.count(),
                next_sessions_cursor,
                catalog,
                protocol_version: GATEWAY_PROTOCOL_VERSION,
                host: app.host.clone(),
                sessions,
                workspaces: app.browser.roots(),
            }
        };
        if before == app.bus.sequence(session_id) {
            if std::env::var_os("PINKCOLLAB_STARTUP_TIMING").is_some() {
                eprintln!(
                    "snapshot scope={} capture_ms={:.3}",
                    if session_id.is_some() {
                        "session"
                    } else {
                        "host"
                    },
                    started.elapsed().as_secs_f64() * 1000.0
                );
            }
            return Some((before, payload));
        }
    }
    None
}
async fn api_usage(
    axum::Extension(usage): axum::Extension<Arc<crate::usage::UsageService>>,
) -> Result<Json<crate::usage::UsageSnapshot>, ApiError> {
    usage.read().await.map(Json).map_err(|error| {
        eprintln!("Usage read failed: {error}");
        ApiError(
            StatusCode::SERVICE_UNAVAILABLE,
            "usage_unavailable",
            "Unable to load provider usage. Check that OMP supports usage --json and try again."
                .into(),
        )
    })
}
#[cfg(test)]
mod tests {
    use super::*;
    use crate::domain::SessionRecord;

    #[test]
    fn unrelated_timeline_during_database_read_does_not_invalidate_snapshots() {
        let runtime = tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .max_blocking_threads(1)
            .build()
            .unwrap();
        runtime.block_on(async {
            let dir = tempfile::tempdir().unwrap();
            let store = Arc::new(Store::open(&dir.path().join("data")).unwrap());
            let browser = Arc::new(Browser::new(&[dir.path().to_owned()]).unwrap());
            let bus = Arc::new(Bus::default());
            let now = chrono::Utc::now();
            let host = Host {
                id: store.host_id().unwrap(),
                name: "test".into(),
                os: "test".into(),
                status: "online".into(),
                omp_version: "test".into(),
                gateway_version: "test".into(),
            };
            store
                .create_v2(
                    "client",
                    "create",
                    "create",
                    SessionRecord {
                        id: "quiet".into(),
                        host_id: host.id.clone(),
                        cwd: crate::workspace::display(dir.path()),
                        title: "Quiet".into(),
                        metadata_revision: 1,
                        created_at: now,
                        updated_at: now,
                        last_activity_at: now,
                        archived_at: None,
                        engine_session_ref: None,
                    },
                )
                .unwrap();
            let sessions = SessionDirectory::new(
                store.clone(),
                browser.clone(),
                bus.clone(),
                "unused".into(),
                vec![],
                1,
            );
            let app = App {
                host,
                store,
                browser,
                bus: bus.clone(),
                sessions,
            };
            for scope in [None, Some("quiet")] {
                // Hold the only blocking worker to force the event between snapshot's
                // first marker read and its database result, without sleeps or timing guesses.
                let (started_tx, started_rx) = tokio::sync::oneshot::channel();
                let (release_tx, release_rx) = std::sync::mpsc::channel();
                let blocker = tokio::task::spawn_blocking(move || {
                    started_tx.send(()).unwrap();
                    release_rx.recv().unwrap();
                });
                started_rx.await.unwrap();
                let before = bus.sequence(scope);
                let capture = snapshot(&app, scope);
                tokio::pin!(capture);
                assert!(futures_util::poll!(&mut capture).is_pending());
                bus.publish(ServerEvent::Timeline {
                    session_id: "noisy".into(),
                    upsert: vec![],
                    remove: vec![],
                    reset: false,
                });
                release_tx.send(()).unwrap();
                let (fence, payload) = capture
                    .await
                    .expect("unrelated output must not prevent capture");
                assert_eq!(
                    fence, before,
                    "capture must not retry against unrelated events"
                );
                assert_eq!(payload.session_id(), scope);
                blocker.await.unwrap();
            }
            // A host mutation during the same blocked capture must instead advance
            // the fence and appear in both the bounded selection and full ledger.
            let (started_tx, started_rx) = tokio::sync::oneshot::channel();
            let (release_tx, release_rx) = std::sync::mpsc::channel();
            let blocker = tokio::task::spawn_blocking(move || {
                started_tx.send(()).unwrap();
                release_rx.recv().unwrap();
            });
            started_rx.await.unwrap();
            let capture = snapshot(&app, None);
            tokio::pin!(capture);
            assert!(futures_util::poll!(&mut capture).is_pending());
            let mut added = app.store.v2_session("quiet").unwrap().unwrap();
            added.id = "added".into();
            app.store
                .create_v2("client", "added", "added", added.clone())
                .unwrap();
            bus.publish(ServerEvent::SessionUpsert {
                session_id: added.id.clone(),
                summary: crate::protocol::SessionSummary {
                    session: SessionDto::from(&added),
                    runtime: None,
                },
            });
            release_tx.send(()).unwrap();
            let (fence, payload) = capture.await.unwrap();
            assert_eq!(fence, bus.sequence(None));
            let ServerEvent::HostSnapshot {
                sessions,
                total_sessions,
                catalog,
                ..
            } = payload
            else {
                panic!("host snapshot expected")
            };
            assert_eq!(total_sessions, 2);
            assert_eq!(catalog.count(), 2);
            assert!(sessions.iter().any(|s| s.session.id == "added"));
            assert!(bus.replay(&bus.epoch, fence, None).unwrap().is_empty());
            blocker.await.unwrap();
        });
    }
}
