//! Session HTTP request validation and response mapping. Mutation fencing lives in SessionDirectory.
use super::*;
use crate::{
    domain::SessionRecord,
    runtime::{Command as ApiCommand, CreateError, SubmitError, UploadError},
};
use axum::body::Bytes;
use serde::Serialize;
use sha2::{Digest, Sha256};

#[derive(Deserialize)]
pub(super) struct ApiSessionsQuery {
    cursor: Option<String>,
    limit: Option<usize>,
}
#[derive(Serialize, Deserialize)]
struct ApiSessionsCursor {
    created_at: String,
    id: String,
}
pub(super) async fn api_sessions(
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
    let page = app
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
    let next_cursor = if page.has_more {
        page.sessions.last().map(|summary| {
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
    let mut summaries = serde_json::to_value(page.sessions).unwrap();
    for summary in summaries.as_array_mut().unwrap() {
        crate::wire::compact_runtime(&mut summary["runtime"]);
    }
    Ok(Json(
        json!({"sessions":summaries,"nextCursor":next_cursor,"epoch":app.bus.epoch,"catalogVersion":page.catalog_version}),
    ))
}
pub(super) async fn api_detail(
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

pub(super) async fn api_delete(
    State(app): State<App>,
    Path(id): Path<String>,
) -> Result<StatusCode, ApiError> {
    // Finish cleanup and publication even if the requesting phone disconnects.
    let result = tokio::spawn(async move { app.sessions.delete(&id).await })
        .await
        .map_err(|_| {
            ApiError(
                StatusCode::SERVICE_UNAVAILABLE,
                "delete_failed",
                "Session deletion worker stopped; retry deletion".into(),
            )
        })?;
    result.map_err(|error| match error {
        crate::runtime::DeleteError::Busy => ApiError(
            StatusCode::CONFLICT,
            "session_busy",
            "Exit the session runtime and wait for pending actions before deleting".into(),
        ),
        crate::runtime::DeleteError::ExternalBusy => ApiError(
            StatusCode::CONFLICT,
            "external_session_busy",
            "Close the external OMP session before deleting".into(),
        ),
        crate::runtime::DeleteError::Failed => ApiError(
            StatusCode::SERVICE_UNAVAILABLE,
            "delete_failed",
            "Could not finish deleting session files; retry deletion".into(),
        ),
    })?;
    Ok(StatusCode::NO_CONTENT)
}
#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub(super) struct ApiCommandBody {
    command_id: String,
    #[serde(flatten)]
    command: ApiCommand,
}
pub(super) async fn api_command(
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
pub(super) struct UploadQuery {
    name: String,
}
pub(super) async fn api_upload(
    State(app): State<App>,
    Path((id, file_id)): Path<(String, String)>,
    Query(query): Query<UploadQuery>,
    bytes: Bytes,
) -> Result<(StatusCode, Json<Value>), ApiError> {
    let size = bytes.len();
    let path = app
        .sessions
        .upload(id, file_id.clone(), query.name, bytes)
        .await
        .map_err(|error| match error {
            UploadError::NotFound => ApiError(
                StatusCode::NOT_FOUND,
                "session_not_found",
                "Session not found".into(),
            ),
            UploadError::Persistence => ApiError(
                StatusCode::SERVICE_UNAVAILABLE,
                "persistence_unavailable",
                "Session record unavailable".into(),
            ),
            UploadError::Invalid => ApiError(
                StatusCode::BAD_REQUEST,
                "invalid_file",
                "Invalid upload request".into(),
            ),
            UploadError::Failed => ApiError(
                StatusCode::SERVICE_UNAVAILABLE,
                "upload_failed",
                "Could not store uploaded file".into(),
            ),
        })?;
    Ok((
        StatusCode::CREATED,
        Json(
            json!({"fileId":file_id,"name":path.file_name().and_then(|n| n.to_str()).unwrap_or("file"),"size":size}),
        ),
    ))
}
pub(super) async fn api_operation(
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
pub(super) async fn api_models(
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
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub(super) struct ApiCreate {
    command_id: String,
    host_id: String,
    cwd: String,
    #[serde(default)]
    title: String,
}
pub(super) async fn api_create(
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
        last_activity_at: now,
        archived_at: None,
        engine_session_ref: None,
    };
    let (record, replayed) = app
        .sessions
        .create(client_id, body.command_id, fingerprint, record)
        .await
        .map_err(|error| match error {
            CreateError::Deleted => ApiError(
                StatusCode::GONE,
                "session_deleted",
                "The session created by this request was deleted".into(),
            ),
            CreateError::Conflict => ApiError(
                StatusCode::CONFLICT,
                "idempotency_conflict",
                "Command ID was already used for a different session request".into(),
            ),
            CreateError::Persistence => ApiError(
                StatusCode::SERVICE_UNAVAILABLE,
                "persistence_unavailable",
                "Could not save session".into(),
            ),
        })?;
    Ok((
        if replayed {
            StatusCode::OK
        } else {
            StatusCode::CREATED
        },
        Json(json!(SessionDto::from(&record))),
    ))
}
