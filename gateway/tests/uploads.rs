#![cfg(feature = "test-fixtures")]
mod support;
use pinkcollab_gateway::{
    storage::Store,
    uploads::{self, PromptFileMode},
};
use serde_json::{Value, json};
use std::time::Duration;
use support::{Harness, wait_operation};

#[tokio::test]
async fn uploaded_files_reach_the_matching_omp_session() {
    let h = Harness::new(1, vec!["--record-prompt-frame".into()]).await;
    let client = reqwest::Client::new();
    let credential = h.pair().await;
    let sessions = format!("{}/api/v2/sessions", h.url);
    let record: Value = client
        .post(&sessions)
        .bearer_auth(&credential)
        .json(&json!({"commandId":"create","hostId":h.host.id,"cwd":h.cwd("uploads")}))
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    let session = format!("{sessions}/{}", record["id"].as_str().unwrap());
    let image_id = format!("file_{}", "a".repeat(32));
    let document_id = format!("file_{}", "b".repeat(32));
    let image = {
        let mut cursor = std::io::Cursor::new(Vec::new());
        image::GrayImage::from_pixel(1, 1, image::Luma([255]))
            .write_to(&mut cursor, image::ImageFormat::Png)
            .unwrap();
        cursor.into_inner()
    };
    let image_url = format!("{session}/files/{image_id}");
    assert_eq!(
        client
            .put(&image_url)
            .query(&[("name", "photo sample.png")])
            .body(image.clone())
            .send()
            .await
            .unwrap()
            .status(),
        401
    );
    assert_eq!(
        client
            .put(&image_url)
            .bearer_auth(&credential)
            .query(&[("name", "photo sample.png")])
            .body(image.clone())
            .send()
            .await
            .unwrap()
            .status(),
        201
    );
    assert_eq!(
        client
            .put(&image_url)
            .bearer_auth(&credential)
            .query(&[("name", "photo sample.png")])
            .body(image.clone())
            .send()
            .await
            .unwrap()
            .status(),
        201
    );
    assert_eq!(
        client
            .put(&image_url)
            .bearer_auth(&credential)
            .query(&[("name", "photo sample.png")])
            .body(b"changed".to_vec())
            .send()
            .await
            .unwrap()
            .status(),
        400
    );
    assert_eq!(
        client
            .put(format!(
                "{session}/files/{}",
                "file_c".to_owned() + &"c".repeat(31)
            ))
            .bearer_auth(&credential)
            .query(&[("name", "../escape.png")])
            .body(image.clone())
            .send()
            .await
            .unwrap()
            .status(),
        400
    );
    let document = vec![b'x'; 600_000];
    assert_eq!(
        client
            .put(format!("{session}/files/{document_id}"))
            .bearer_auth(&credential)
            .query(&[("name", "notes.txt")])
            .body(document.clone())
            .send()
            .await
            .unwrap()
            .status(),
        201
    );
    let commands = format!("{session}/commands");
    assert_eq!(client.post(&commands).bearer_auth(&credential)
        .json(&json!({"commandId":"prompt-files","type":"prompt","delivery":"start","message":"","fileIds":[image_id,document_id]}))
        .send().await.unwrap().status(), 202);
    wait_operation(
        &client,
        &format!("{session}/operations/prompt-files"),
        &credential,
        "succeeded",
    )
    .await;
    let frame: Value = serde_json::from_slice(
        &std::fs::read(h.dir.path().join("projects/uploads/last-prompt.json")).unwrap(),
    )
    .unwrap();
    let image_path = h
        .store
        .uploads_dir()
        .join(record["id"].as_str().unwrap())
        .join(format!("file_{}", "a".repeat(32)))
        .join("photo sample.png");
    let message = frame["message"].as_str().unwrap();
    assert!(message.contains(&format!("@\"{}\"", image_path.display())));
    assert!(message.contains("@\"") && message.contains("notes.txt"));
    assert!(
        frame.get("images").is_none(),
        "direct prompts use OMP file mentions"
    );
    assert_eq!(std::fs::read(image_path).unwrap(), image);
    let (_, queued_images) = uploads::prompt_files(
        &h.store,
        record["id"].as_str().unwrap(),
        &[format!("file_{}", "a".repeat(32))],
        PromptFileMode::Queued,
    )
    .unwrap();
    assert_eq!(queued_images[0]["type"], "image");
    assert_eq!(queued_images[0]["mimeType"], "image/png");

    let other: Value = client
        .post(&sessions)
        .bearer_auth(&credential)
        .json(&json!({"commandId":"create-other","hostId":h.host.id,"cwd":h.cwd("other-uploads")}))
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    let other_session = format!("{sessions}/{}", other["id"].as_str().unwrap());
    assert_eq!(client.post(format!("{other_session}/commands")).bearer_auth(&credential)
        .json(&json!({"commandId":"foreign-file","type":"prompt","delivery":"start","message":"","fileIds":[format!("file_{}", "a".repeat(32))]}))
        .send().await.unwrap().status(), 202);
    let failed: Value = tokio::time::timeout(Duration::from_secs(5), async {
        loop {
            let operation: Value = client
                .get(format!("{other_session}/operations/foreign-file"))
                .bearer_auth(&credential)
                .send()
                .await
                .unwrap()
                .json()
                .await
                .unwrap();
            if operation["status"] == "failed" {
                break operation;
            }
            tokio::time::sleep(Duration::from_millis(20)).await;
        }
    })
    .await
    .unwrap();
    assert_eq!(failed["error"]["code"], "invalid_file");
}

#[test]
fn concurrent_uploads_publish_only_complete_files() {
    let dir = tempfile::tempdir().unwrap();
    let store = std::sync::Arc::new(Store::open(dir.path()).unwrap());
    let session_id = format!("sess_{}", "a".repeat(32));
    let file_id = format!("file_{}", "b".repeat(32));
    let barrier = std::sync::Arc::new(std::sync::Barrier::new(2));
    let attempts = std::thread::scope(|scope| {
        let handles = [b'a', b'b'].map(|byte| {
            let store = store.clone();
            let barrier = barrier.clone();
            let session_id = session_id.clone();
            let file_id = file_id.clone();
            scope.spawn(move || {
                barrier.wait();
                uploads::save(
                    &store,
                    &session_id,
                    &file_id,
                    "sample.bin",
                    &vec![byte; 1024 * 1024],
                )
            })
        });
        handles.map(|handle| handle.join().unwrap())
    });
    assert_eq!(attempts.iter().filter(|result| result.is_ok()).count(), 1);
    let published = uploads::resolve(&store, &session_id, &file_id).unwrap();
    let bytes = std::fs::read(&published).unwrap();
    assert!(bytes == vec![b'a'; 1024 * 1024] || bytes == vec![b'b'; 1024 * 1024]);
    assert_eq!(
        std::fs::read_dir(store.uploads_dir().join(&session_id))
            .unwrap()
            .count(),
        1
    );
    assert_eq!(
        uploads::save(&store, &session_id, &file_id, "sample.bin", &bytes).unwrap(),
        published
    );
}

#[test]
fn invalid_existing_upload_directory_is_not_replaced() {
    let dir = tempfile::tempdir().unwrap();
    let store = Store::open(dir.path()).unwrap();
    let session_id = format!("sess_{}", "a".repeat(32));
    let file_id = format!("file_{}", "b".repeat(32));
    let file_dir = store.uploads_dir().join(&session_id).join(&file_id);
    std::fs::create_dir_all(&file_dir).unwrap();
    std::fs::write(file_dir.join("old.txt"), b"old").unwrap();
    std::fs::write(file_dir.join("extra.txt"), b"extra").unwrap();
    assert!(uploads::save(&store, &session_id, &file_id, "new.txt", b"new").is_err());
    assert!(!file_dir.join("new.txt").exists());
    assert!(uploads::resolve(&store, &session_id, &file_id).is_err());
}

#[tokio::test]
async fn queued_image_over_frame_limit_fails_instead_of_disappearing() {
    let h = Harness::new(1, vec![]).await;
    let client = reqwest::Client::new();
    let credential = h.pair().await;
    let session: Value = client
        .post(format!("{}/api/v2/sessions", h.url))
        .bearer_auth(&credential)
        .json(&json!({"commandId":"create-frame-limit","hostId":h.host.id,"cwd":h.cwd("frame-limit")}))
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    let session_url = format!(
        "{}/api/v2/sessions/{}",
        h.url,
        session["id"].as_str().unwrap()
    );
    let file_id = format!("file_{}", "c".repeat(32));
    let mut image = b"\x89PNG\r\n\x1a\n".to_vec();
    image.resize(500_000, 0);
    assert_eq!(
        client
            .put(format!("{session_url}/files/{file_id}"))
            .bearer_auth(&credential)
            .query(&[("name", "large.png")])
            .body(image)
            .send()
            .await
            .unwrap()
            .status(),
        201
    );
    assert_eq!(
        client
            .post(format!("{session_url}/commands"))
            .bearer_auth(&credential)
            .json(&json!({
                "commandId":"oversized-queued-image",
                "type":"prompt",
                "delivery":"steer",
                "expectedGeneration":"gen-test",
                "message":"\\".repeat(250_000),
                "fileIds":[file_id],
            }))
            .send()
            .await
            .unwrap()
            .status(),
        202
    );
    let failure: Value = tokio::time::timeout(Duration::from_secs(5), async {
        loop {
            let operation: Value = client
                .get(format!("{session_url}/operations/oversized-queued-image"))
                .bearer_auth(&credential)
                .send()
                .await
                .unwrap()
                .json()
                .await
                .unwrap();
            if operation["status"] == "failed" {
                break operation;
            }
            tokio::time::sleep(Duration::from_millis(20)).await;
        }
    })
    .await
    .unwrap();
    assert_eq!(failure["error"]["code"], "invalid_request");
}
