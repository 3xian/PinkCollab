mod stream;
use crate::{
    domain::SessionRecord,
    events::Bus,
    model::Host,
    protocol::{GATEWAY_PROTOCOL_VERSION, OperationDto, ServerEvent, SessionDto, SessionSnapshot},
    runtime::{Command as ApiCommand, SessionDirectory, SubmitError},
    storage::Store,
    uploads,
    workspace::Browser,
};
use axum::{
    Json, Router,
    body::Bytes,
    extract::{DefaultBodyLimit, Path, Query, State},
    http::{HeaderMap, StatusCode},
    middleware::{self, Next},
    response::{IntoResponse, Response},
    routing::{any, get, post, put},
};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use sha2::{Digest, Sha256};
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
        .route("/api/v4/sessions/{id}", get(api_detail))
        .route("/api/v4/sessions/{id}/commands", post(api_command))
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
struct ApiSessionsQuery {
    cursor: Option<String>,
    limit: Option<usize>,
}
#[derive(Serialize, Deserialize)]
struct ApiSessionsCursor {
    created_at: String,
    id: String,
}
async fn api_sessions(
    State(app): State<App>,
    Query(query): Query<ApiSessionsQuery>,
) -> Result<Json<Value>, ApiError> {
    let limit = query.limit.unwrap_or(50);
    if !(1..=100).contains(&limit) {
        return Err(ApiError(
            StatusCode::BAD_REQUEST,
            "invalid_page_limit",
            "Limit must be 1..100".into(),
        ));
    }
    let cursor = match query.cursor.as_deref() {
        None => None,
        Some(raw) => {
            let decoded = (raw.len() <= 1024)
                .then(|| hex::decode(raw).ok())
                .flatten()
                .and_then(|bytes| serde_json::from_slice::<ApiSessionsCursor>(&bytes).ok());
            Some(decoded.ok_or_else(|| {
                ApiError(
                    StatusCode::BAD_REQUEST,
                    "invalid_cursor",
                    "Invalid sessions cursor".into(),
                )
            })?)
        }
    };
    let (sessions, has_more) = app
        .sessions
        .list_page(
            cursor
                .as_ref()
                .map(|cursor| (cursor.created_at.as_str(), cursor.id.as_str())),
            limit,
        )
        .await
        .map_err(|_| {
            ApiError(
                StatusCode::SERVICE_UNAVAILABLE,
                "persistence_unavailable",
                "Session records unavailable".into(),
            )
        })?;
    let next_cursor = if has_more {
        sessions.last().map(|summary| {
            hex::encode(
                serde_json::to_vec(&ApiSessionsCursor {
                    created_at: summary.session.created_at.to_rfc3339(),
                    id: summary.session.id.clone(),
                })
                .expect("serializable cursor"),
            )
        })
    } else {
        None
    };
    let mut summaries = serde_json::to_value(sessions).unwrap();
    for summary in summaries.as_array_mut().unwrap() {
        crate::wire::compact_runtime(&mut summary["runtime"]);
    }
    Ok(Json(
        json!({"sessions":summaries,"nextCursor":next_cursor,"epoch":app.bus.epoch}),
    ))
}
async fn api_detail(
    State(app): State<App>,
    Path(id): Path<String>,
) -> Result<Json<SessionSnapshot>, ApiError> {
    let view = app
        .sessions
        .view(&id)
        .await
        .map_err(|_| {
            ApiError(
                StatusCode::SERVICE_UNAVAILABLE,
                "persistence_unavailable",
                "Session record unavailable".into(),
            )
        })?
        .ok_or_else(|| {
            ApiError(
                StatusCode::NOT_FOUND,
                "session_not_found",
                "Session not found".into(),
            )
        })?;
    Ok(Json(view.dto()))
}
#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct ApiCommandBody {
    command_id: String,
    #[serde(flatten)]
    command: ApiCommand,
}
async fn api_command(
    State(app): State<App>,
    headers: HeaderMap,
    Path(id): Path<String>,
    Json(body): Json<ApiCommandBody>,
) -> Result<(StatusCode, Json<Value>), ApiError> {
    let client_id = api_client(&app, &headers).await?;
    let submitted = app
        .sessions
        .submit(client_id, id, body.command_id, body.command)
        .await
        .map_err(|err| match err {
            SubmitError::HistoryUnavailable => ApiError(
                StatusCode::SERVICE_UNAVAILABLE,
                "history_unavailable",
                "OMP history cannot be continued; refresh and retry".into(),
            ),
            SubmitError::ExternalBusy => ApiError(
                StatusCode::CONFLICT,
                "external_session_busy",
                "Close the external OMP session and retry".into(),
            ),
            SubmitError::NotFound => ApiError(
                StatusCode::NOT_FOUND,
                "session_not_found",
                "Session not found".into(),
            ),
            SubmitError::Invalid(message) => {
                ApiError(StatusCode::BAD_REQUEST, "invalid_request", message)
            }
            SubmitError::Conflict => ApiError(
                StatusCode::CONFLICT,
                "idempotency_conflict",
                "Command ID was already used for a different request".into(),
            ),
            SubmitError::Persistence => ApiError(
                StatusCode::SERVICE_UNAVAILABLE,
                "persistence_unavailable",
                "Could not store command receipt".into(),
            ),
        })?;
    Ok((
        if submitted.replayed {
            StatusCode::OK
        } else {
            StatusCode::ACCEPTED
        },
        Json(
            json!({"operation":OperationDto::from(&submitted.receipt),"receiptStored":submitted.receipt_stored}),
        ),
    ))
}
#[derive(Deserialize)]
struct UploadQuery {
    name: String,
}
async fn api_upload(
    State(app): State<App>,
    Path((id, file_id)): Path<(String, String)>,
    Query(query): Query<UploadQuery>,
    bytes: Bytes,
) -> Result<(StatusCode, Json<Value>), ApiError> {
    let read_id = id.clone();
    let exists = app.sessions.view(&read_id).await.map_err(|_| {
        ApiError(
            StatusCode::SERVICE_UNAVAILABLE,
            "persistence_unavailable",
            "Session record unavailable".into(),
        )
    })?;
    if exists.is_none() {
        return Err(ApiError(
            StatusCode::NOT_FOUND,
            "session_not_found",
            "Session not found".into(),
        ));
    }
    let size = bytes.len();
    let name = query.name;
    let store = app.store.clone();
    let saved_file_id = file_id.clone();
    let result = tokio::task::spawn_blocking(move || {
        uploads::save(&store, &id, &saved_file_id, &name, &bytes)
    })
    .await
    .map_err(|_| {
        ApiError(
            StatusCode::SERVICE_UNAVAILABLE,
            "upload_failed",
            "Upload worker stopped".into(),
        )
    })?;
    let path = result.map_err(|err| {
        if err.downcast_ref::<std::io::Error>().is_some() {
            ApiError(
                StatusCode::SERVICE_UNAVAILABLE,
                "upload_failed",
                "Could not store uploaded file".into(),
            )
        } else {
            ApiError(
                StatusCode::BAD_REQUEST,
                "invalid_file",
                "Invalid upload request".into(),
            )
        }
    })?;
    Ok((
        StatusCode::CREATED,
        Json(
            json!({"fileId":file_id,"name":path.file_name().and_then(|n| n.to_str()).unwrap_or("file"),"size":size}),
        ),
    ))
}
async fn api_operation(
    State(app): State<App>,
    headers: HeaderMap,
    Path((id, command_id)): Path<(String, String)>,
) -> Result<Json<Value>, ApiError> {
    let client_id = api_client(&app, &headers).await?;
    let operation = app
        .sessions
        .operation(&client_id, &id, &command_id)
        .await
        .map_err(|_| {
            ApiError(
                StatusCode::SERVICE_UNAVAILABLE,
                "persistence_unavailable",
                "Command receipt unavailable".into(),
            )
        })?
        .ok_or_else(|| {
            ApiError(
                StatusCode::NOT_FOUND,
                "operation_not_found",
                "Command receipt not found".into(),
            )
        })?;
    Ok(Json(json!(OperationDto::from(&operation))))
}
async fn api_models(
    State(app): State<App>,
    Path(id): Path<String>,
) -> Result<Json<Value>, ApiError> {
    let (models, thinking_levels) = app.sessions.models(&id).await.map_err(|err| {
        let code = if err.to_string().contains("runtime") {
            "runtime_required"
        } else {
            "unsupported_capability"
        };
        ApiError(
            StatusCode::CONFLICT,
            code,
            "Model catalog unavailable".into(),
        )
    })?;
    Ok(Json(
        json!({"models":models,"thinkingLevels":thinking_levels}),
    ))
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
    let view = app
        .sessions
        .history_view(&id)
        .await
        .map_err(|_| {
            ApiError(
                StatusCode::SERVICE_UNAVAILABLE,
                "history_unavailable",
                "History unavailable".into(),
            )
        })?
        .ok_or_else(|| {
            ApiError(
                StatusCode::NOT_FOUND,
                "session_not_found",
                "Session not found".into(),
            )
        })?;
    let Some(path) = view.session.engine_session_ref else {
        return Ok(Json(json!({"items":[],"source":null,"nextCursor":null})));
    };
    crate::history::sync_history(FsPath::new(&path), &id, &request)
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
    let view = app
        .sessions
        .history_view(&id)
        .await
        .map_err(|_| unavailable())?
        .ok_or_else(|| {
            ApiError(
                StatusCode::NOT_FOUND,
                "session_not_found",
                "Session not found".into(),
            )
        })?;
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

async fn read_history(app: &App, id: &str, query: &ApiHistoryQuery) -> Result<Value, ApiError> {
    let read_id = id.to_owned();
    let session = app
        .sessions
        .history_view(&read_id)
        .await
        .map_err(|_| {
            ApiError(
                StatusCode::SERVICE_UNAVAILABLE,
                "history_unavailable",
                "OMP history is unavailable".into(),
            )
        })?
        .ok_or_else(|| {
            ApiError(
                StatusCode::NOT_FOUND,
                "session_not_found",
                "Session not found".into(),
            )
        })?;
    let Some(reference) = session.session.engine_session_ref else {
        return Ok(json!({"items":[],"source":null,"nextCursor":null}));
    };
    if tokio::fs::metadata(&reference)
        .await
        .err()
        .is_some_and(|err| err.kind() == std::io::ErrorKind::NotFound)
    {
        let prompt_session = id.to_owned();
        let missing_reference = reference.clone();
        let unwritten = app
            .store
            .run(move |store| store.reference_is_unwritten(&prompt_session, &missing_reference))
            .await
            .map_err(|_| {
                ApiError(
                    StatusCode::SERVICE_UNAVAILABLE,
                    "persistence_unavailable",
                    "Session record unavailable".into(),
                )
            })?;
        if unwritten {
            return Ok(json!({"items":[],"source":null,"nextCursor":null}));
        }
    }
    let page = crate::history::history_page_with_anchor(
        FsPath::new(&reference),
        id,
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
#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct ApiCreate {
    command_id: String,
    host_id: String,
    cwd: String,
    #[serde(default)]
    title: String,
}
async fn api_create(
    State(app): State<App>,
    headers: HeaderMap,
    Json(body): Json<ApiCreate>,
) -> Result<(StatusCode, Json<Value>), ApiError> {
    let client_id = api_client(&app, &headers).await?;
    if body.host_id != app.host.id
        || body.command_id.is_empty()
        || body.command_id.len() > 128
        || !body
            .command_id
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b == b'_' || b == b'-')
    {
        return Err(ApiError(
            StatusCode::BAD_REQUEST,
            "invalid_request",
            "Valid hostId and commandId required".into(),
        ));
    }
    let cwd = app.browser.validate(FsPath::new(&body.cwd)).map_err(|_| {
        ApiError(
            StatusCode::FORBIDDEN,
            "workspace_forbidden",
            "Workspace is outside the allowed roots".into(),
        )
    })?;
    let cwd = crate::workspace::display(&cwd);
    let title = if body.title.trim().is_empty() {
        "New session".to_owned()
    } else {
        body.title.trim().chars().take(80).collect::<String>()
    };
    let semantics = json!({"type":"create_session","hostId":body.host_id,"cwd":cwd,"title":title});
    let fingerprint = hex::encode(Sha256::digest(serde_json::to_vec(&semantics).unwrap()));
    let now = chrono::Utc::now();
    let record = SessionRecord {
        id: crate::storage::id("sess_"),
        host_id: app.host.id.clone(),
        cwd,
        title,
        metadata_revision: 1,
        created_at: now,
        updated_at: now,
        archived_at: None,
        engine_session_ref: None,
    };
    let command_id = body.command_id.clone();
    let (record, replayed) = app
        .store
        .run(move |store| store.create_v2(&client_id, &command_id, &fingerprint, record))
        .await
        .map_err(|err| {
            if err.to_string().contains("idempotency_conflict") {
                ApiError(
                    StatusCode::CONFLICT,
                    "idempotency_conflict",
                    "Command ID was already used for a different session request".into(),
                )
            } else {
                ApiError(
                    StatusCode::SERVICE_UNAVAILABLE,
                    "persistence_unavailable",
                    "Could not save session".into(),
                )
            }
        })?;
    if !replayed {
        app.sessions.publish_created(&record).await;
    }
    Ok((
        if replayed {
            StatusCode::OK
        } else {
            StatusCode::CREATED
        },
        Json(json!(SessionDto::from(&record))),
    ))
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
    let started = std::time::Instant::now();
    for _ in 0..5 {
        let before = app.bus.sequence(session_id);
        let payload = if let Some(id) = session_id {
            ServerEvent::SessionSnapshot {
                session_id: id.into(),
                snapshot: app.sessions.view(id).await.ok()??.dto(),
            }
        } else {
            ServerEvent::HostSnapshot {
                protocol_version: GATEWAY_PROTOCOL_VERSION,
                host: app.host.clone(),
                sessions: app.sessions.list().await.ok()?,
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
        });
    }
}
