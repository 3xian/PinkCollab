//! Optional synthetic cost evidence, not a wall-clock performance assertion.
use pinkcollab_gateway::{
    domain::SessionRecord, events::Bus, runtime::SessionDirectory, storage::Store,
    workspace::Browser,
};
use sha2::{Digest, Sha256};
use std::{sync::Arc, time::Instant};

#[tokio::test]
#[ignore = "local synthetic catalog measurement"]
async fn compare_full_directory_capture_with_bounded_bootstrap() {
    let root = tempfile::tempdir().unwrap();
    let files = tempfile::tempdir().unwrap();
    let data = tempfile::tempdir().unwrap();
    let store = Arc::new(Store::open(data.path()).unwrap());
    let host_id = store.host_id().unwrap();
    let now = chrono::Utc::now();
    for index in 0..10_000 {
        let stamp = now - chrono::Duration::seconds(index);
        let session = SessionRecord {
            id: format!("s{index:05}"),
            host_id: host_id.clone(),
            cwd: root.path().to_string_lossy().into_owned(),
            title: format!("Session {index}"),
            metadata_revision: 1,
            created_at: stamp,
            updated_at: stamp,
            last_activity_at: stamp,
            archived_at: None,
            engine_session_ref: None,
        };
        store
            .create_v2("bench", &session.id.clone(), "fp", session)
            .unwrap();
    }
    let directory = SessionDirectory::new(
        store,
        Arc::new(Browser::new(&[root.path().into()]).unwrap()),
        Arc::new(Bus::default()),
        "unused".into(),
        vec![
            "--session-dir".into(),
            files.path().to_string_lossy().into_owned(),
        ],
        1,
    );
    let start = Instant::now();
    let full = directory.list().await.unwrap();
    // Reproduce the previous connection path: build every summary, serialize all
    // to JSON values, fingerprint every Session, sort/hash the full ledger, then
    // truncate to the wire bootstrap. Both paths use the current additive DTO.
    let mut projected = serde_json::to_value(&full).unwrap();
    let values = projected.as_array_mut().unwrap();
    let mut fingerprints: Vec<_> = values
        .iter()
        .map(|s| {
            (
                s["session"]["id"].as_str().unwrap().to_owned(),
                hex::encode(Sha256::digest(serde_json::to_vec(&s["session"]).unwrap())),
            )
        })
        .collect();
    fingerprints.sort_unstable_by(|a, b| a.0.cmp(&b.0));
    let _version = hex::encode(Sha256::digest(serde_json::to_vec(&fingerprints).unwrap()));
    values.truncate(50);
    let full_bytes = serde_json::to_vec(&projected).unwrap().len();
    let full_ms = start.elapsed().as_secs_f64() * 1000.0;
    let start = Instant::now();
    let (cold, _, ledger) = directory.bootstrap(None).await.unwrap();
    let cold_bytes = serde_json::to_vec(&cold).unwrap().len();
    let cold_ms = start.elapsed().as_secs_f64() * 1000.0;
    let start = Instant::now();
    let (warm, cursor, _) = directory.bootstrap(None).await.unwrap();
    let warm_bytes = serde_json::to_vec(&warm).unwrap().len();
    let warm_ms = start.elapsed().as_secs_f64() * 1000.0;
    assert_eq!(full.len(), 10_000);
    assert_eq!(ledger.count(), 10_000);
    assert_eq!(cold.len(), 50);
    assert_eq!(warm.len(), 50);
    assert!(cursor.is_some());
    eprintln!(
        "synthetic 10000 detached managed sessions; capture + summary projection/serialization: legacy full={full_ms:.3}ms/{full_bytes}B; bootstrap cold ledger={cold_ms:.3}ms/{cold_bytes}B; bootstrap warm ledger={warm_ms:.3}ms/{warm_bytes}B"
    );
}
