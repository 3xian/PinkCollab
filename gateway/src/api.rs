use crate::{
    events::Bus,
    model::{Event, Host, PROTOCOL_VERSION},
    session::{InputResponse, Registry},
    storage::Store,
    workspace::Browser,
};
use axum::{
    Json, Router,
    extract::{
        DefaultBodyLimit, Path, Query, State, WebSocketUpgrade,
        ws::{Message, WebSocket},
    },
    http::{HeaderMap, StatusCode},
    middleware::{self, Next},
    response::{IntoResponse, Response},
    routing::{get, post},
};
use futures_util::{SinkExt, StreamExt};
use serde::Deserialize;
use serde_json::{Value, json};
use std::{path::Path as FsPath, sync::Arc, time::Duration};
use tower_http::compression::CompressionLayer;

#[derive(Clone)]
pub struct App {
    pub host: Host,
    pub store: Arc<Store>,
    pub browser: Arc<Browser>,
    pub registry: Arc<Registry>,
    pub bus: Arc<Bus>,
}
type ApiResult = Result<Json<Value>, ApiError>;
pub struct ApiError(StatusCode, String);
impl IntoResponse for ApiError {
    fn into_response(self) -> Response {
        (self.0, Json(json!({"error":self.1}))).into_response()
    }
}
/// `Display` on an anyhow error stops at the outermost context; `{:#}` keeps the whole chain, so
/// every API error that wraps one goes through here.
fn reason(error: &anyhow::Error) -> String {
    format!("{error:#}")
}
fn invalid(e: anyhow::Error) -> ApiError {
    ApiError(StatusCode::CONFLICT, reason(&e))
}
pub fn router(app: App) -> Router {
    let protected = Router::new()
        .route("/api/v1/host", get(host))
        .route("/api/v1/workspaces", get(workspaces))
        .route("/api/v1/fs/list", get(list))
        .route("/api/v1/sessions", get(sessions).post(create))
        .route("/api/v1/sessions/{id}", get(detail).delete(delete))
        .route("/api/v1/sessions/{id}/prompt", post(prompt))
        .route("/api/v1/sessions/{id}/interrupt", post(interrupt))
        .route("/api/v1/sessions/{id}/stop", post(stop))
        .route("/api/v1/sessions/{id}/respond", post(respond))
        .route("/api/v1/sessions/{id}/models", get(available_models))
        .route("/api/v1/sessions/{id}/model", post(select_model))
        .route("/api/v1/sessions/{id}/model/cycle", post(cycle_model))
        .route("/api/v1/events", get(stream))
        .route_layer(middleware::from_fn_with_state(app.clone(), authenticate));
    Router::new()
        .route("/api/v1/pair", post(pair))
        .merge(protected)
        .layer(DefaultBodyLimit::max(512 * 1024))
        .layer(middleware::from_fn(security_headers))
        .layer(CompressionLayer::new())
        .with_state(app)
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
    if !credential(request.headers()).is_some_and(|token| app.store.authenticate(token)) {
        return ApiError(
            StatusCode::UNAUTHORIZED,
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
async fn pair(
    State(app): State<App>,
    Json(body): Json<Pair>,
) -> Result<(StatusCode, Json<Value>), ApiError> {
    if body.token.len() != 48 || body.name.trim().is_empty() || body.name.len() > 100 {
        return Err(ApiError(
            StatusCode::BAD_REQUEST,
            "token and client name required".into(),
        ));
    }
    let (id, credential) = app.store.pair(&body.token, &body.name).map_err(|_| {
        ApiError(
            StatusCode::UNAUTHORIZED,
            "invalid or expired pairing token".into(),
        )
    })?;
    Ok((
        StatusCode::CREATED,
        Json(
            json!({"clientId":id,"credential":credential,"host":app.host,"protocolVersion":PROTOCOL_VERSION}),
        ),
    ))
}
async fn host(State(app): State<App>) -> Json<Value> {
    Json(json!(app.host))
}
async fn workspaces(State(app): State<App>) -> Json<Value> {
    Json(json!(app.browser.roots()))
}
#[derive(Deserialize)]
struct ListQuery {
    path: String,
}
async fn list(State(app): State<App>, Query(query): Query<ListQuery>) -> ApiResult {
    let listing = app
        .browser
        .list(FsPath::new(&query.path))
        .await
        .map_err(|e| ApiError(StatusCode::FORBIDDEN, reason(&e)))?;
    Ok(Json(json!(listing)))
}
async fn sessions(State(app): State<App>) -> Json<Value> {
    Json(json!(app.registry.list().await))
}
async fn detail(State(app): State<App>, Path(id): Path<String>) -> ApiResult {
    let detail = app
        .registry
        .detail(&id)
        .await
        .map_err(|e| ApiError(StatusCode::NOT_FOUND, reason(&e)))?;
    Ok(Json(json!(detail)))
}
#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct Create {
    host_id: String,
    cwd: String,
    #[serde(default)]
    prompt: String,
    #[serde(default)]
    title: String,
}
async fn create(
    State(app): State<App>,
    Json(body): Json<Create>,
) -> Result<(StatusCode, Json<Value>), ApiError> {
    let s = app
        .registry
        .create(body.host_id, body.cwd, body.prompt, body.title)
        .await
        .map_err(|e| ApiError(StatusCode::UNPROCESSABLE_ENTITY, reason(&e)))?;
    Ok((StatusCode::CREATED, Json(json!(s))))
}
async fn delete(State(app): State<App>, Path(id): Path<String>) -> Result<StatusCode, ApiError> {
    app.registry.delete(&id).await.map_err(invalid)?;
    Ok(StatusCode::NO_CONTENT)
}
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Prompt {
    message: String,
}
async fn prompt(
    State(app): State<App>,
    Path(id): Path<String>,
    Json(body): Json<Prompt>,
) -> ApiResult {
    app.registry
        .command(id, "prompt".into(), body.message, InputResponse::default())
        .await
        .map_err(invalid)?;
    Ok(Json(json!({"ok":true})))
}
async fn interrupt(State(app): State<App>, Path(id): Path<String>) -> ApiResult {
    app.registry
        .command(
            id,
            "interrupt".into(),
            String::new(),
            InputResponse::default(),
        )
        .await
        .map_err(invalid)?;
    Ok(Json(json!({"ok":true})))
}
async fn stop(State(app): State<App>, Path(id): Path<String>) -> ApiResult {
    app.registry
        .command(id, "stop".into(), String::new(), InputResponse::default())
        .await
        .map_err(invalid)?;
    Ok(Json(json!({"ok":true})))
}
async fn respond(
    State(app): State<App>,
    Path(id): Path<String>,
    Json(body): Json<InputResponse>,
) -> ApiResult {
    app.registry
        .command(id, "respond".into(), String::new(), body)
        .await
        .map_err(invalid)?;
    Ok(Json(json!({"ok":true})))
}
async fn cycle_model(State(app): State<App>, Path(id): Path<String>) -> ApiResult {
    let model = app.registry.cycle_model(&id).await.map_err(invalid)?;
    Ok(Json(json!({"model":model})))
}
async fn available_models(State(app): State<App>, Path(id): Path<String>) -> ApiResult {
    let models = app.registry.available_models(&id).await.map_err(invalid)?;
    Ok(Json(json!({"models":models})))
}
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct SelectModel {
    provider: String,
    id: String,
    role: String,
}
async fn select_model(
    State(app): State<App>,
    Path(id): Path<String>,
    Json(body): Json<SelectModel>,
) -> ApiResult {
    let model = app
        .registry
        .select_model(&id, &body.provider, &body.id, &body.role)
        .await
        .map_err(invalid)?;
    Ok(Json(json!({"model":model})))
}

async fn stream(State(app): State<App>, headers: HeaderMap, upgrade: WebSocketUpgrade) -> Response {
    let token = credential(&headers).unwrap_or_default().to_owned();
    upgrade
        .max_message_size(1024)
        .on_upgrade(move |socket| events(socket, app, token))
}
async fn send(
    socket: &mut futures_util::stream::SplitSink<WebSocket, Message>,
    event: &Event,
) -> bool {
    let Ok(text) = serde_json::to_string(event) else {
        return false;
    };
    matches!(
        tokio::time::timeout(
            Duration::from_secs(10),
            socket.send(Message::Text(text.into()))
        )
        .await,
        Ok(Ok(()))
    )
}
async fn events(socket: WebSocket, app: App, token: String) {
    // Subscribe first, then snapshot: concurrent updates are queued and cannot disappear in the REST/WS gap.
    let mut receiver = app.bus.subscribe();
    let (mut tx, mut rx) = socket.split();
    let snapshot = Event {
        sequence: 0,
        kind: "snapshot".into(),
        timestamp: chrono::Utc::now(),
        payload: json!({
            "host": app.host,
            "sessions": app.registry.list().await,
            "workspaces": app.browser.roots(),
            "protocolVersion": PROTOCOL_VERSION,
        }),
    };
    if !send(&mut tx, &snapshot).await {
        return;
    }
    let mut ticker = tokio::time::interval(Duration::from_secs(25));
    let mut last_seen = tokio::time::Instant::now();
    loop {
        tokio::select! {
            event = receiver.recv() => {
                let Ok(event) = event else { break; };
                if event.kind == "gateway.shutdown" || !send(&mut tx, &event).await { break; }
            }
            _ = ticker.tick() => {
                if !app.store.authenticate(&token) || last_seen.elapsed() > Duration::from_secs(70) { break; }
                if !matches!(tokio::time::timeout(Duration::from_secs(10), tx.send(Message::Ping(vec![].into()))).await, Ok(Ok(()))) { break; }
            }
            message = rx.next() => {
                match message {
                    Some(Ok(Message::Pong(_))) => last_seen = tokio::time::Instant::now(),
                    Some(Ok(Message::Ping(value))) => {
                        last_seen = tokio::time::Instant::now();
                        if !matches!(tokio::time::timeout(Duration::from_secs(10), tx.send(Message::Pong(value))).await, Ok(Ok(()))) { break; }
                    },
                    Some(Ok(Message::Close(_))) | None | Some(Err(_)) => break,
                    _ => {},
                }
            }
        }
    }
    let _ = tokio::time::timeout(Duration::from_secs(2), tx.send(Message::Close(None))).await;
}
