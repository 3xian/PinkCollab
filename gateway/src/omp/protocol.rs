use super::{MAX_LINE, TransportError, TransportErrorKind, frame::Decoder, string};
use serde_json::{Value, json};
use std::time::{Duration, Instant};
use tokio::sync::watch;

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(super) enum Version {
    V1,
    V2,
}

#[derive(Clone, Copy, Debug)]
pub(super) struct Limits {
    pub version: Version,
    pub frame_bytes: usize,
}

#[derive(Clone, Debug)]
pub(super) enum Phase {
    AwaitingReady,
    Negotiating(Limits),
    Ready(Limits),
    Failed(TransportError),
}

/// A snapshot of reader-owned progress. One supervisor enforces the active deadline.
#[derive(Clone, Debug)]
pub(super) struct Progress {
    pub phase: Phase,
    pub deadline: Option<Instant>,
}

impl Progress {
    pub fn starting() -> Self {
        Self {
            phase: Phase::AwaitingReady,
            deadline: Some(Instant::now() + Duration::from_secs(30)),
        }
    }

    pub fn frame_limit(&self) -> usize {
        match self.phase {
            Phase::Negotiating(limits) | Phase::Ready(limits) => limits.frame_bytes,
            _ => MAX_LINE,
        }
    }

    pub fn timeout_error(&self) -> TransportError {
        let detail = match self.phase {
            Phase::AwaitingReady => "OMP startup timed out",
            Phase::Negotiating(_) => "OMP protocol negotiation timed out",
            _ => "OMP rpc chunk sequence timed out",
        };
        TransportError::new(TransportErrorKind::Timeout, detail)
    }
}

pub(super) enum Action {
    Command(Value),
    Frame(Value),
    Pending,
}

pub(super) struct Protocol {
    decoder: Decoder,
    progress: watch::Sender<Progress>,
}

impl Protocol {
    pub fn new(progress: watch::Sender<Progress>) -> Self {
        Self {
            decoder: Decoder::default(),
            progress,
        }
    }

    pub fn frame_limit(&self) -> usize {
        self.decoder.frame_limit
    }
    pub fn incomplete(&self) -> bool {
        self.decoder.incomplete()
    }

    fn publish(&self, phase: Phase, deadline: Option<Instant>) {
        // Shutdown is terminal even if the pipe still delivers buffered stdout.
        self.progress.send_if_modified(|state| {
            if matches!(state.phase, Phase::Failed(_)) {
                return false;
            }
            *state = Progress { phase, deadline };
            true
        });
    }

    pub fn accept(&mut self, bytes: &[u8]) -> Result<Action, TransportError> {
        let value: Value = serde_json::from_slice(bytes).map_err(|error| {
            TransportError::new(
                TransportErrorKind::InvalidJson,
                format!("OMP invalid RPC JSON: {error}"),
            )
        })?;
        if !value.is_object() {
            return Err(TransportError::new(
                TransportErrorKind::InvalidFrame,
                "RPC frame must be an object",
            ));
        }
        let phase = self.progress.borrow().phase.clone();
        if let Phase::Failed(error) = phase {
            return Err(error);
        }
        if string(&value, "type") == "rpc_chunk" {
            if !matches!(
                phase,
                Phase::Ready(Limits {
                    version: Version::V2,
                    ..
                })
            ) {
                return Err(TransportError::new(
                    TransportErrorKind::InvalidChunk,
                    "rpc_chunk before RPC v2 negotiation",
                ));
            }
            if !self.decoder.incomplete() {
                self.publish(
                    phase.clone(),
                    Some(Instant::now() + Duration::from_secs(15)),
                );
            }
        }
        let value =
            match self.decoder.push(value).map_err(|error| {
                TransportError::from_error(error, TransportErrorKind::InvalidChunk)
            })? {
                Some(value) => value,
                None => return Ok(Action::Pending),
            };
        if matches!(phase, Phase::Ready(_)) && self.progress.borrow().deadline.is_some() {
            self.publish(phase.clone(), None);
        }
        match (phase, string(&value, "type")) {
            (Phase::AwaitingReady, "ready") => {
                let v2 = self.decoder.configure(&value).map_err(|error| {
                    TransportError::from_error(error, TransportErrorKind::Negotiation)
                })?;
                if bytes.len() > self.decoder.frame_limit {
                    return Err(TransportError::new(
                        TransportErrorKind::PhysicalFrameTooLarge,
                        "OMP ready exceeded its advertised NDJSON transport limit",
                    ));
                }
                let limits = Limits {
                    frame_bytes: self.decoder.frame_limit,
                    version: if v2 { Version::V2 } else { Version::V1 },
                };
                if v2 {
                    self.publish(
                        Phase::Negotiating(limits),
                        Some(Instant::now() + Duration::from_secs(10)),
                    );
                    Ok(Action::Command(
                        json!({"id":"protocol-1","type":"negotiate_protocol","protocolVersion":2}),
                    ))
                } else {
                    self.publish(Phase::Ready(limits), None);
                    Ok(Action::Pending)
                }
            }
            (Phase::Negotiating(limits), "response") if string(&value, "id") == "protocol-1" => {
                if value["success"] != true || value["data"]["protocolVersion"] != 2 {
                    return Err(TransportError::new(
                        TransportErrorKind::Negotiation,
                        format!("OMP RPC v2 negotiation failed: {value}"),
                    ));
                }
                self.publish(Phase::Ready(limits), None);
                Ok(Action::Pending)
            }
            (_, "ready") => Err(TransportError::new(
                TransportErrorKind::Negotiation,
                "OMP duplicate ready frame",
            )),
            _ => Ok(Action::Frame(value)),
        }
    }
}
