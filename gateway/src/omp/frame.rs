use super::{MAX_LINE, TransportError, TransportErrorKind, string};
use anyhow::{Context, Result, ensure};
use base64::{Engine, engine::general_purpose::STANDARD};
use serde_json::Value;

const MAX_REASSEMBLED: usize = 64 * 1024 * 1024;
const CHUNK_BYTES: usize = 256 * 1024;

pub(super) struct Decoder {
    pub frame_limit: usize,
    logical_limit: usize,
    pending: Option<Chunks>,
}

struct Chunks {
    id: String,
    count: usize,
    length: usize,
    next: usize,
    bytes: Vec<u8>,
}

impl Default for Decoder {
    fn default() -> Self {
        Self {
            frame_limit: MAX_LINE,
            logical_limit: MAX_REASSEMBLED,
            pending: None,
        }
    }
}

impl Decoder {
    pub fn configure(&mut self, ready: &Value) -> Result<bool> {
        if let Some(version) = ready.get("protocolVersion") {
            ensure!(
                matches!(version.as_u64(), Some(1 | 2)),
                "invalid OMP protocolVersion"
            );
        }
        let supports_v2 = match ready.get("supportedProtocolVersions") {
            None => false,
            Some(versions) => {
                let versions = versions
                    .as_array()
                    .context("invalid supportedProtocolVersions")?;
                ensure!(
                    versions.iter().all(|v| v.as_u64().is_some()),
                    "invalid supportedProtocolVersions"
                );
                versions.iter().any(|v| v.as_u64() == Some(2))
            }
        };
        self.frame_limit = limit(ready, "maxFrameBytes", MAX_LINE, supports_v2)?;
        self.logical_limit = limit(
            ready,
            "maxReassembledFrameBytes",
            MAX_REASSEMBLED,
            supports_v2,
        )?;
        ensure!(
            self.logical_limit >= self.frame_limit,
            "invalid OMP transport limits"
        );
        Ok(supports_v2)
    }

    pub fn incomplete(&self) -> bool {
        self.pending.is_some()
    }

    pub fn push(&mut self, value: Value) -> Result<Option<Value>> {
        ensure!(value.is_object(), "RPC frame must be an object");
        if string(&value, "type") != "rpc_chunk" {
            ensure!(self.pending.is_none(), "rpc chunk sequence interrupted");
            return Ok(Some(value));
        }
        let id = string(&value, "chunkId");
        let index = integer(&value, "index")?;
        let count = integer(&value, "count")?;
        let length = integer(&value, "byteLength")?;
        ensure!(
            length <= self.logical_limit,
            TransportError::new(
                TransportErrorKind::ReassemblyTooLarge,
                "rpc chunk reassembly limit exceeded"
            )
        );
        ensure!(
            !id.is_empty()
                && id.len() <= 128
                && (2..=MAX_REASSEMBLED / CHUNK_BYTES).contains(&count)
                && index < count
                && length >= self.frame_limit
                && length <= self.logical_limit,
            "invalid rpc chunk metadata or reassembly limit"
        );
        ensure!(
            length
                <= count
                    .checked_mul(CHUNK_BYTES)
                    .context("rpc chunk count overflow")?
                && count <= length,
            "invalid rpc chunk count and byteLength"
        );
        let data = value["data"].as_str().context("invalid rpc chunk data")?;
        ensure!(
            !data.is_empty() && data.len() % 4 == 0,
            "invalid rpc chunk base64 data length"
        );
        let padding = data
            .as_bytes()
            .iter()
            .rev()
            .take_while(|&&b| b == b'=')
            .count();
        ensure!(padding <= 2, "invalid rpc chunk base64 padding");
        let decoded_length = data.len() / 4 * 3 - padding;
        ensure!(
            decoded_length > 0 && decoded_length <= CHUNK_BYTES,
            TransportError::new(
                TransportErrorKind::ChunkPayloadTooLarge,
                "rpc chunk payload exceeds transport limit"
            )
        );
        if self.pending.is_none() {
            ensure!(index == 0, "rpc chunk sequence must start at index 0");
            // Reserve only after validating the bounded declaration. This prevents geometric
            // Vec growth beyond the logical cap and avoids a second full-payload copy.
            let mut bytes = Vec::new();
            bytes
                .try_reserve_exact(length)
                .context("cannot allocate rpc reassembly buffer")?;
            self.pending = Some(Chunks {
                id: id.to_owned(),
                count,
                length,
                next: 0,
                bytes,
            });
        }
        let pending = self.pending.as_mut().unwrap();
        ensure!(
            pending.id == id
                && pending.count == count
                && pending.length == length
                && pending.next == index,
            "rpc chunk sequence mismatch"
        );
        let start = pending.bytes.len();
        let received = start
            .checked_add(decoded_length)
            .context("rpc chunk length overflow")?;
        ensure!(
            received <= length,
            "rpc chunk sequence exceeds declared length"
        );
        pending.bytes.resize(received, 0);
        // Decode into the final buffer: no per-chunk allocation or payload copy.
        // STANDARD requires canonical padding and rejects nonzero trailing bits.
        let decoded = STANDARD
            .decode_slice(data, &mut pending.bytes[start..])
            .context("invalid rpc chunk base64")?;
        ensure!(
            decoded == decoded_length,
            "rpc chunk decoded length mismatch"
        );
        pending.next += 1;
        if pending.next < count {
            return Ok(None);
        }
        let pending = self.pending.take().unwrap();
        ensure!(
            pending.bytes.len() == length,
            "rpc chunk sequence length mismatch"
        );
        let text = std::str::from_utf8(&pending.bytes).map_err(|error| {
            TransportError::new(
                TransportErrorKind::InvalidUtf8,
                format!("invalid rpc chunk UTF-8: {error}"),
            )
        })?;
        let frame: Value = serde_json::from_str(text).map_err(|error| {
            TransportError::new(
                TransportErrorKind::InvalidJson,
                format!("invalid reassembled RPC JSON: {error}"),
            )
        })?;
        ensure!(
            frame.is_object() && string(&frame, "type") != "rpc_chunk",
            TransportError::new(
                TransportErrorKind::InvalidFrame,
                "reassembled RPC frame must be a non-chunk object"
            )
        );
        Ok(Some(frame))
    }
}

fn integer(value: &Value, key: &str) -> Result<usize> {
    value[key]
        .as_u64()
        .and_then(|n| usize::try_from(n).ok())
        .with_context(|| format!("invalid rpc chunk {key}"))
}

fn limit(value: &Value, key: &str, local: usize, required: bool) -> Result<usize> {
    match value.get(key) {
        None if !required => Ok(local),
        _ => {
            let n = integer(value, key)?;
            ensure!(n > 0, "invalid OMP {key}");
            Ok(n.min(local))
        }
    }
}
