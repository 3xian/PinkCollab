use std::fmt;

/// Stable failure classes; diagnostic text may change without changing the transport contract.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum TransportErrorKind {
    PhysicalFrameTooLarge,
    ChunkPayloadTooLarge,
    ReassemblyTooLarge,
    InvalidChunk,
    InvalidUtf8,
    InvalidJson,
    InvalidFrame,
    IncompleteChunk,
    Negotiation,
    Timeout,
    Io,
}

#[derive(Clone, Debug)]
pub struct TransportError {
    pub kind: TransportErrorKind,
    detail: String,
}

impl TransportError {
    pub(super) fn new(kind: TransportErrorKind, detail: impl Into<String>) -> Self {
        Self {
            kind,
            detail: detail.into(),
        }
    }

    pub(super) fn from_error(error: anyhow::Error, fallback: TransportErrorKind) -> Self {
        error
            .downcast_ref::<Self>()
            .cloned()
            .unwrap_or_else(|| Self::new(fallback, format!("{error:#}")))
    }
}

impl fmt::Display for TransportError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.detail)
    }
}

impl std::error::Error for TransportError {}
