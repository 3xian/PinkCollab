use crate::{
    events::Bus,
    model::Host,
    storage::Store,
    v2_model::{GATEWAY_PROTOCOL_VERSION, SessionRecord},
    v2_runtime::{Command as V2Command, SessionDirectory, SubmitError},
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
    routing::{any, get, post},
};
use futures_util::{SinkExt, StreamExt};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use sha2::{Digest, Sha256};
use std::{collections::HashMap, path::Path as FsPath, sync::Arc, time::Duration};
use tower_http::compression::CompressionLayer;

#[derive(Clone)]
pub struct App {
    pub host: Host,
    pub store: Arc<Store>,
    pub browser: Arc<Browser>,
    pub bus: Arc<Bus>,
    pub v2: Arc<SessionDirectory>,
}
pub fn router(app: App) -> Router {
    let protected = Router::new()
        .route("/api/v2/host", get(v2_host))
        .route("/api/v2/workspaces", get(workspaces))
        .route("/api/v2/fs/list", get(list))
        .route("/api/v2/sessions", get(v2_sessions).post(v2_create))
        .route("/api/v2/sessions/{id}", get(v2_detail))
        .route("/api/v2/sessions/{id}/commands", post(v2_command))
        .route(
            "/api/v2/sessions/{id}/operations/{command_id}",
            get(v2_operation),
        )
        .route("/api/v2/sessions/{id}/models", get(v2_models))
        .route("/api/v2/sessions/{id}/history", get(v2_history))
        .route("/api/v2/events", get(v2_stream))
        .route_layer(middleware::from_fn_with_state(app.clone(), authenticate));
    Router::new()
        .route("/api/v2/pair", post(v2_pair))
        .route("/api/v1/{*path}", any(upgrade_required))
        .merge(protected)
        .layer(DefaultBodyLimit::max(512 * 1024))
        .layer(middleware::from_fn(security_headers))
        .layer(CompressionLayer::new())
        .with_state(app)
}

struct V2Error(StatusCode, &'static str, String);
impl IntoResponse for V2Error {
    fn into_response(self) -> Response {
        (self.0, Json(json!({"code":self.1,"message":self.2}))).into_response()
    }
}
fn v2_client(app: &App, headers: &HeaderMap) -> Result<String, V2Error> {
    credential(headers)
        .and_then(|token| app.store.client_id(token))
        .ok_or_else(|| {
            V2Error(
                StatusCode::UNAUTHORIZED,
                "authentication_required",
                "Paired client credential required".into(),
            )
        })
}
async fn v2_pair(
    State(app): State<App>,
    Json(body): Json<Pair>,
) -> Result<(StatusCode, Json<Value>), V2Error> {
    if body.token.len() != 48 || body.name.trim().is_empty() || body.name.len() > 100 {
        return Err(V2Error(
            StatusCode::BAD_REQUEST,
            "invalid_request",
            "Token and client name required".into(),
        ));
    }
    let (client_id, credential) = app.store.pair(&body.token, &body.name).map_err(|_| {
        V2Error(
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
async fn v2_host(State(app): State<App>) -> Json<Value> {
    Json(
        json!({"host":app.host,"protocolVersion":GATEWAY_PROTOCOL_VERSION,"capabilities":{"ompRpcTransport":1,"modelSelection":true,"thinkingLevels":true,"historyPaging":true,"resume":true}}),
    )
}
#[derive(Deserialize)]
struct V2SessionsQuery {
    cursor: Option<String>,
    limit: Option<usize>,
}
#[derive(Serialize, Deserialize)]
struct V2SessionsCursor {
    created_at: String,
    id: String,
}
async fn v2_sessions(
    State(app): State<App>,
    Query(query): Query<V2SessionsQuery>,
) -> Result<Json<Value>, V2Error> {
    let limit = query.limit.unwrap_or(50);
    if !(1..=100).contains(&limit) {
        return Err(V2Error(
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
                .and_then(|bytes| serde_json::from_slice::<V2SessionsCursor>(&bytes).ok());
            Some(decoded.ok_or_else(|| {
                V2Error(
                    StatusCode::BAD_REQUEST,
                    "invalid_cursor",
                    "Invalid sessions cursor".into(),
                )
            })?)
        }
    };
    let (sessions, has_more) = app
        .v2
        .list_page(
            cursor
                .as_ref()
                .map(|cursor| (cursor.created_at.as_str(), cursor.id.as_str())),
            limit,
        )
        .await
        .map_err(|_| {
            V2Error(
                StatusCode::SERVICE_UNAVAILABLE,
                "persistence_unavailable",
                "Session records unavailable".into(),
            )
        })?;
    let next_cursor = if has_more {
        sessions
            .last()
            .and_then(|summary| summary.get("session"))
            .and_then(|session| {
                Some(hex::encode(
                    serde_json::to_vec(&V2SessionsCursor {
                        created_at: chrono::DateTime::parse_from_rfc3339(
                            session.get("createdAt")?.as_str()?,
                        )
                        .ok()?
                        .to_rfc3339(),
                        id: session.get("id")?.as_str()?.to_owned(),
                    })
                    .ok()?,
                ))
            })
    } else {
        None
    };
    Ok(Json(json!({"sessions":sessions,"nextCursor":next_cursor})))
}
async fn v2_detail(State(app): State<App>, Path(id): Path<String>) -> Result<Json<Value>, V2Error> {
    let view = app
        .v2
        .view(&id)
        .await
        .map_err(|_| {
            V2Error(
                StatusCode::SERVICE_UNAVAILABLE,
                "persistence_unavailable",
                "Session record unavailable".into(),
            )
        })?
        .ok_or_else(|| {
            V2Error(
                StatusCode::NOT_FOUND,
                "session_not_found",
                "Session not found".into(),
            )
        })?;
    Ok(Json(json!(view)))
}
#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct V2CommandBody {
    command_id: String,
    #[serde(flatten)]
    command: V2Command,
}
async fn v2_command(
    State(app): State<App>,
    headers: HeaderMap,
    Path(id): Path<String>,
    Json(body): Json<V2CommandBody>,
) -> Result<(StatusCode, Json<Value>), V2Error> {
    let client_id = v2_client(&app, &headers)?;
    let submitted = app
        .v2
        .submit(client_id, id, body.command_id, body.command)
        .await
        .map_err(|err| match err {
            SubmitError::NotFound => V2Error(
                StatusCode::NOT_FOUND,
                "session_not_found",
                "Session not found".into(),
            ),
            SubmitError::Invalid(message) => {
                V2Error(StatusCode::BAD_REQUEST, "invalid_request", message)
            }
            SubmitError::Conflict => V2Error(
                StatusCode::CONFLICT,
                "idempotency_conflict",
                "Command ID was already used for a different request".into(),
            ),
            SubmitError::Persistence => V2Error(
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
        Json(json!({"operation":submitted.receipt,"receiptStored":submitted.receipt_stored})),
    ))
}
async fn v2_operation(
    State(app): State<App>,
    headers: HeaderMap,
    Path((id, command_id)): Path<(String, String)>,
) -> Result<Json<Value>, V2Error> {
    let client_id = v2_client(&app, &headers)?;
    let operation = app
        .v2
        .operation(&client_id, &id, &command_id)
        .map_err(|_| {
            V2Error(
                StatusCode::SERVICE_UNAVAILABLE,
                "persistence_unavailable",
                "Command receipt unavailable".into(),
            )
        })?
        .ok_or_else(|| {
            V2Error(
                StatusCode::NOT_FOUND,
                "operation_not_found",
                "Command receipt not found".into(),
            )
        })?;
    Ok(Json(json!(operation)))
}
async fn v2_models(State(app): State<App>, Path(id): Path<String>) -> Result<Json<Value>, V2Error> {
    let (models, thinking_levels) = app.v2.models(&id).await.map_err(|err| {
        let code = if err.to_string().contains("runtime") {
            "runtime_required"
        } else {
            "unsupported_capability"
        };
        V2Error(StatusCode::CONFLICT, code, err.to_string())
    })?;
    Ok(Json(
        json!({"models":models,"thinkingLevels":thinking_levels}),
    ))
}
#[derive(Deserialize)]
struct V2HistoryQuery {
    cursor: Option<String>,
    limit: Option<usize>,
}
async fn v2_history(
    State(app): State<App>,
    Path(id): Path<String>,
    Query(query): Query<V2HistoryQuery>,
) -> Result<Json<Value>, V2Error> {
    let session = app
        .store
        .v2_session(&id)
        .map_err(|_| {
            V2Error(
                StatusCode::SERVICE_UNAVAILABLE,
                "persistence_unavailable",
                "Session record unavailable".into(),
            )
        })?
        .ok_or_else(|| {
            V2Error(
                StatusCode::NOT_FOUND,
                "session_not_found",
                "Session not found".into(),
            )
        })?;
    let Some(reference) = session.engine_session_ref else {
        return Ok(Json(json!({"items":[],"source":null,"nextCursor":null})));
    };
    let page = crate::history::history_page(
        FsPath::new(&reference),
        &id,
        query.cursor.as_deref(),
        query.limit.unwrap_or(50),
    )
    .await
    .map_err(|err| {
        let raw = format!("{err:#}");
        if raw.contains("stale_cursor") {
            V2Error(
                StatusCode::CONFLICT,
                "stale_cursor",
                "History changed; reload from the first page".into(),
            )
        } else if raw.contains("invalid_page_limit") {
            V2Error(
                StatusCode::BAD_REQUEST,
                "invalid_page_limit",
                "Limit must be 1..100".into(),
            )
        } else {
            V2Error(
                StatusCode::SERVICE_UNAVAILABLE,
                "history_unavailable",
                "OMP history is unavailable".into(),
            )
        }
    })?;
    Ok(Json(page))
}
#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct V2Create {
    command_id: String,
    host_id: String,
    cwd: String,
    #[serde(default)]
    title: String,
}
async fn v2_create(
    State(app): State<App>,
    headers: HeaderMap,
    Json(body): Json<V2Create>,
) -> Result<(StatusCode, Json<Value>), V2Error> {
    let client_id = v2_client(&app, &headers)?;
    if body.host_id != app.host.id
        || body.command_id.is_empty()
        || body.command_id.len() > 128
        || !body
            .command_id
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b == b'_' || b == b'-')
    {
        return Err(V2Error(
            StatusCode::BAD_REQUEST,
            "invalid_request",
            "Valid hostId and commandId required".into(),
        ));
    }
    let cwd = app.browser.validate(FsPath::new(&body.cwd)).map_err(|_| {
        V2Error(
            StatusCode::FORBIDDEN,
            "workspace_forbidden",
            "Workspace is outside the allowed roots".into(),
        )
    })?;
    let cwd = crate::workspace::display(&cwd);
    let title = if body.title.trim().is_empty() {
        FsPath::new(&cwd)
            .file_name()
            .and_then(|name| name.to_str())
            .unwrap_or("New session")
            .chars()
            .take(80)
            .collect::<String>()
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
    let (record, replayed) = app
        .store
        .create_v2(&client_id, &body.command_id, &fingerprint, record)
        .map_err(|err| {
            if err.to_string().contains("idempotency_conflict") {
                V2Error(
                    StatusCode::CONFLICT,
                    "idempotency_conflict",
                    "Command ID was already used for a different session request".into(),
                )
            } else {
                V2Error(
                    StatusCode::SERVICE_UNAVAILABLE,
                    "persistence_unavailable",
                    "Could not save session".into(),
                )
            }
        })?;
    if !replayed {
        app.bus.publish_resource(
            "host/sessions",
            json!([{"type":"session.created","value":record}]),
        );
    }
    Ok((
        if replayed {
            StatusCode::OK
        } else {
            StatusCode::CREATED
        },
        Json(json!(record)),
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
    if !credential(request.headers()).is_some_and(|token| app.store.authenticate(token)) {
        return V2Error(
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
        return V2Error(
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
    V2Error(
        StatusCode::UPGRADE_REQUIRED,
        "protocol_upgrade_required",
        "Gateway API v1 is no longer supported; update PinkCollab".into(),
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
) -> Result<Json<Value>, V2Error> {
    let listing = app
        .browser
        .list(FsPath::new(&query.path))
        .await
        .map_err(|_| {
            V2Error(
                StatusCode::FORBIDDEN,
                "workspace_forbidden",
                "Directory is outside the allowed roots or unavailable".into(),
            )
        })?;
    Ok(Json(json!(listing)))
}
async fn v2_stream(
    State(app): State<App>,
    headers: HeaderMap,
    upgrade: WebSocketUpgrade,
) -> Response {
    let token = credential(&headers).unwrap_or_default().to_owned();
    upgrade
        .max_message_size(4096)
        .on_upgrade(move |socket| events_v2(socket, app, token))
}
async fn send_value(
    tx: &mut futures_util::stream::SplitSink<WebSocket, Message>,
    value: &Value,
) -> bool {
    let Ok(encoded) = serde_json::to_string(value) else {
        return false;
    };
    matches!(
        tokio::time::timeout(
            Duration::from_secs(10),
            tx.send(Message::Text(encoded.into()))
        )
        .await,
        Ok(Ok(()))
    )
}
struct Subscription {
    id: String,
    cursor: crate::v2_model::Cursor,
}
async fn v2_snapshot(
    app: &App,
    resource: &str,
    subscription_id: &str,
) -> Option<(crate::v2_model::Cursor, Value)> {
    // The receiver is registered before this function. Retrying a changing resource avoids a
    // snapshot with a cursor newer than the payload; changes after the cursor stay queued.
    for _ in 0..5 {
        let before = app.bus.cursor(resource);
        let payload = if resource == "host/sessions" {
            json!({"host":app.host,"sessions":app.v2.list().await.ok()?,"workspaces":app.browser.roots(),"protocolVersion":GATEWAY_PROTOCOL_VERSION})
        } else {
            let id = resource.strip_prefix("session/")?;
            json!(app.v2.view(id).await.ok()??)
        };
        let after = app.bus.cursor(resource);
        if before.epoch == after.epoch && before.revision == after.revision {
            return Some((
                before.clone(),
                json!({"type":"snapshot","subscriptionId":subscription_id,"resource":resource,"cursor":before,"payload":payload}),
            ));
        }
    }
    None
}
async fn events_v2(socket: WebSocket, app: App, token: String) {
    let mut receiver = app.bus.subscribe();
    let (mut tx, mut rx) = socket.split();
    let mut subscriptions = HashMap::<String, Subscription>::new();
    let host_resource = "host/sessions";
    let host_id = crate::storage::id("sub_");
    let Some((cursor, snapshot)) = v2_snapshot(&app, host_resource, &host_id).await else {
        return;
    };
    subscriptions.insert(
        host_resource.into(),
        Subscription {
            id: host_id,
            cursor,
        },
    );
    if !send_value(&mut tx, &snapshot).await {
        return;
    }
    let mut ticker = tokio::time::interval(Duration::from_secs(25));
    let mut last_seen = tokio::time::Instant::now();
    loop {
        tokio::select! {
            event=receiver.recv()=>{
                let event=match event {Ok(event)=>event,Err(_)=>{
                    let _=send_value(&mut tx,&json!({"type":"resync_required","reason":"subscription_backlog"})).await;
                    break
                }};
                if event.kind=="gateway.shutdown" {break}
                if event.kind!="change" {continue}
                let Some(resource)=event.payload["resource"].as_str() else {continue};
                let Some(subscription)=subscriptions.get_mut(resource) else {continue};
                let epoch=event.payload["epoch"].as_str().unwrap_or_default();
                let base=event.payload["baseRevision"].as_u64().unwrap_or(0);
                let revision=event.payload["revision"].as_u64().unwrap_or(0);
                if epoch!=subscription.cursor.epoch || base!=subscription.cursor.revision {
                    if revision<=subscription.cursor.revision && epoch==subscription.cursor.epoch {continue}
                    if !send_value(&mut tx,&json!({"type":"resync_required","subscriptionId":subscription.id,"resource":resource})).await {break}
                    subscriptions.remove(resource);
                    continue;
                }
                let outbound=json!({"type":"change","subscriptionId":subscription.id,"resource":resource,"epoch":epoch,"baseRevision":base,"revision":revision,"changes":event.payload["changes"]});
                if !send_value(&mut tx,&outbound).await {break}
                subscription.cursor.revision=revision;
            }
            _=ticker.tick()=>{
                if !app.store.authenticate(&token) || last_seen.elapsed()>Duration::from_secs(70){break}
                if !matches!(tokio::time::timeout(Duration::from_secs(10),tx.send(Message::Ping(vec![].into()))).await,Ok(Ok(()))){break}
            }
            message=rx.next()=>{
                match message {
                    Some(Ok(Message::Pong(_)))=>last_seen=tokio::time::Instant::now(),
                    Some(Ok(Message::Ping(value)))=>{
                        last_seen=tokio::time::Instant::now();
                        if !matches!(tokio::time::timeout(Duration::from_secs(10),tx.send(Message::Pong(value))).await,Ok(Ok(()))){break}
                    }
                    Some(Ok(Message::Text(raw)))=>{
                        let Ok(command)=serde_json::from_str::<Value>(&raw) else {break};
                        let resource=command["resource"].as_str().unwrap_or_default();
                        if command["type"]=="subscribe" && resource.starts_with("session/") && resource.len()<160 {
                            let subscription_id=crate::storage::id("sub_");
                            let Some((cursor,snapshot))=v2_snapshot(&app,resource,&subscription_id).await else {
                                if !send_value(&mut tx,&json!({"type":"subscription_error","resource":resource,"code":"session_not_found"})).await {break}
                                continue
                            };
                            subscriptions.insert(resource.into(),Subscription{id:subscription_id,cursor});
                            if !send_value(&mut tx,&snapshot).await {break}
                        } else if command["type"]=="unsubscribe" && resource.starts_with("session/") {
                            subscriptions.remove(resource);
                        } else {break}
                    }
                    Some(Ok(Message::Close(_)))|None|Some(Err(_))=>break,
                    _=>{},
                }
            }
        }
    }
    let _ = tokio::time::timeout(Duration::from_secs(2), tx.send(Message::Close(None))).await;
}
