//! Ordered v4 streaming, replay and independent scope recovery.
use super::{ApiHistoryQuery, App, credential, read_history, snapshot};
use crate::{
    events::Event,
    protocol::{GATEWAY_PROTOCOL_VERSION, ServerEvent},
};
use axum::{
    extract::{
        Query, State, WebSocketUpgrade,
        ws::{Message, WebSocket},
    },
    http::HeaderMap,
    response::Response,
};
use flate2::{Compression, write::GzEncoder};
use futures_util::{SinkExt, StreamExt};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use std::{collections::HashMap, io::Write, time::Duration};
const GZIP_SOCKET_PROTOCOL: &str = "pinkcollab.v4.gzip";

fn socket_message(encoded: String, gzip: bool) -> std::io::Result<Message> {
    if encoded.len() > 8 * 1024 * 1024 {
        return Err(std::io::Error::other("socket frame exceeds budget"));
    }
    if gzip && encoded.len() >= 1024 {
        let mut encoder = GzEncoder::new(Vec::new(), Compression::default());
        encoder.write_all(encoded.as_bytes())?;
        return Ok(Message::Binary(encoder.finish()?.into()));
    }
    Ok(Message::Text(encoded.into()))
}

pub(super) async fn api_stream(
    State(app): State<App>,
    headers: HeaderMap,
    upgrade: WebSocketUpgrade,
    Query(resume): Query<StreamResume>,
) -> Response {
    let token = credential(&headers).unwrap_or_default().to_owned();
    let upgrade = upgrade.protocols([GZIP_SOCKET_PROTOCOL]);
    let gzip = upgrade.selected_protocol().is_some();
    upgrade
        .max_message_size(4096)
        .on_upgrade(move |socket| events(socket, app, token, gzip, resume))
}
async fn send_value(
    tx: &mut futures_util::stream::SplitSink<WebSocket, Message>,
    value: &impl Serialize,
    gzip: bool,
) -> bool {
    let Ok(encoded) = serde_json::to_string(value) else {
        return false;
    };
    let Ok(message) = socket_message(encoded, gzip) else {
        return false;
    };
    matches!(
        tokio::time::timeout(Duration::from_secs(10), tx.send(message)).await,
        Ok(Ok(()))
    )
}
#[derive(Default, Deserialize)]
pub(super) struct StreamResume {
    focus: Option<String>,
    catalog: Option<String>,
    epoch: Option<String>,
    sequence: Option<u64>,
}

async fn send_event(
    tx: &mut futures_util::stream::SplitSink<WebSocket, Message>,
    event: &ServerEvent,
    sequence: u64,
    app: &App,
    wire: &mut crate::wire::WireState,
    subscribed: bool,
    gzip: bool,
) -> bool {
    let Ok(value) = serde_json::to_value(event) else {
        return false;
    };
    send_value(
        tx,
        &wire.project(value, subscribed, &app.bus.epoch, sequence),
        gzip,
    )
    .await
}

async fn events(socket: WebSocket, app: App, token: String, gzip: bool, resume: StreamResume) {
    let mut receiver = app.bus.subscribe();
    let (mut tx, mut rx) = socket.split();
    let mut sessions = HashMap::<String, u64>::new();
    let mut wire = crate::wire::WireState::default();
    wire.focus = resume.focus;
    let mut host_fence = resume.sequence.unwrap_or(0);
    let replay = if resume.epoch.as_deref() == Some(app.bus.epoch.as_str()) {
        let Ok(catalog) = app.sessions.list().await else {
            return;
        };
        wire.seed_catalog(catalog.into_iter().map(|s| s.session));
        if resume
            .catalog
            .as_ref()
            .is_none_or(|version| *version == wire.catalog_version())
        {
            app.bus.replay(&app.bus.epoch, host_fence, None)
        } else {
            None
        }
    } else {
        None
    };
    if let Some(replay) = replay {
        for event in replay {
            if let Event::Update { sequence, event } = event {
                if !send_event(&mut tx, &event, sequence, &app, &mut wire, false, gzip).await {
                    return;
                }
                host_fence = sequence;
            }
        }
        if !send_value(&mut tx,&json!({"type":"host_sync","protocolVersion":GATEWAY_PROTOCOL_VERSION,"epoch":app.bus.epoch,"sequence":host_fence,"totalSessions":wire.catalog_count(),"catalogVersion":wire.catalog_version()}),gzip).await {return;}
    } else {
        let Some((fence, initial)) = snapshot(&app, None).await else {
            return;
        };
        host_fence = fence;
        if !send_event(&mut tx, &initial, fence, &app, &mut wire, false, gzip).await {
            return;
        }
    }
    let mut ticker = tokio::time::interval(Duration::from_secs(25));
    let mut last_seen = tokio::time::Instant::now();
    loop {
        tokio::select! {
            received=receiver.recv()=>{
                let updates=match received {
                    Ok(Event::Update {sequence,event})=>vec![Event::Update {sequence,event}],
                    Ok(Event::Disconnect)|Err(tokio::sync::broadcast::error::RecvError::Closed)=>break,
                    Err(tokio::sync::broadcast::error::RecvError::Lagged(_))=>{
                        let mut recovered=Vec::new();
                        let scopes:Vec<_>=std::iter::once((None,host_fence)).chain(sessions.iter().map(|(id,fence)|(Some(id.clone()),*fence))).collect();
                        let mut failed=false;
                        for (scope,fence) in scopes {
                            if let Some(events)=app.bus.replay(&app.bus.epoch,fence,scope.as_deref()) {recovered.extend(events);}
                            else if let Some((new_fence,value))=snapshot(&app,scope.as_deref()).await {
                                if !send_event(&mut tx,&value,new_fence,&app,&mut wire,scope.is_some(),gzip).await {failed=true;break;}
                                if let Some(id)=scope {sessions.insert(id,new_fence);} else {host_fence=new_fence;}
                            } else {failed=true;break;}
                        }
                        if failed {break;}
                        recovered.sort_by_key(|event|match event {Event::Update{sequence,..}=>*sequence,_=>0});
                        recovered
                    }
                };
                let mut failed=false;
                for update in updates {
                    let Event::Update {sequence,event}=update else {continue};
                    let Some(id)=event.session_id() else {continue};
                    let subscribed=sessions.contains_key(id);
                    let relevant=if event.updates_host(){sequence>host_fence || sessions.get(id).is_some_and(|f|sequence>*f)} else {sessions.get(id).is_some_and(|f|sequence>*f)};
                    if !relevant {continue;}
                    if !send_event(&mut tx,&event,sequence,&app,&mut wire,subscribed,gzip).await {failed=true;break;}
                    if event.updates_host(){host_fence=host_fence.max(sequence);}
                    if let Some(fence)=sessions.get_mut(id){*fence=(*fence).max(sequence);}
                }
                if failed {break;}
            }
            _=ticker.tick()=>{
                let check_token = token.clone();
                if !app.store.run(move |store| Ok(store.authenticate(&check_token))).await.unwrap_or(false) || last_seen.elapsed()>Duration::from_secs(70){break}
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
                        let id=command["sessionId"].as_str().unwrap_or_default();
                        if id.is_empty() || id.len()>128 {break}
                        if command["type"]=="subscribe" {
                            wire.focus=Some(id.to_owned());
                            let Some((fence, mut value))=snapshot(&app,Some(id)).await else {break};
                            let replay=command["epoch"].as_str().zip(command["sequence"].as_u64()).and_then(|(epoch,after)|app.bus.replay(epoch,after,Some(id)));
                            if let Some(replay)=replay {
                                if !send_value(&mut tx,&json!({"type":"session_resume","sessionId":id,"epoch":app.bus.epoch,"sequence":command["sequence"]}),gzip).await {break;}
                                let mut failed=false;
                                for event in replay {
                                    if let Event::Update{sequence,event}=event
                                        && sequence<=fence && !send_event(&mut tx,&event,sequence,&app,&mut wire,true,gzip).await {failed=true;break;}
                                }
                                if failed {break;}
                                if let ServerEvent::SessionSnapshot {snapshot,..}=&mut value {snapshot.timeline.clear();snapshot.operations.clear();}
                                let Ok(mut resumed)=serde_json::to_value(&value) else {break};
                                resumed["type"]=json!("session_sync");
                                if !send_value(&mut tx,&wire.project(resumed,true,&app.bus.epoch,fence),gzip).await {break;}
                            } else {
                                // Cached transcripts use incremental REST sync, so do not send an unused inline page.
                                if command["hasCachedHistory"]!=true
                                    && let Some(limit)=command["historyLimit"].as_u64().filter(|n|(1..=100).contains(n))
                                        && let ServerEvent::SessionSnapshot{snapshot,..}=&mut value && snapshot.has_history {
                                        let query=ApiHistoryQuery{cursor:None,limit:Some(limit as usize),anchor:None,oldest:None};
                                        if let Ok(Ok(page))=tokio::time::timeout(Duration::from_secs(1),read_history(&app,id,&query)).await
                                            && serde_json::to_vec(&page).is_ok_and(|bytes|bytes.len()<=256*1024){snapshot.history=Some(page);}
                                    }
                                if !send_event(&mut tx,&value,fence,&app,&mut wire,true,gzip).await {break;}
                            }
                            sessions.insert(id.into(),fence);
                        } else if command["type"]=="unsubscribe" {sessions.remove(id);} else {break}
                    }
                    Some(Ok(Message::Close(_)))|None|Some(Err(_))=>break,
                    _=>{},
                }
            }
        }
    }
    let _ = tokio::time::timeout(Duration::from_secs(2), tx.send(Message::Close(None))).await;
}
