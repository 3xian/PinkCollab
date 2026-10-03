use super::support::Harness;
use serde_json::Value;
#[tokio::test]
async fn usage_requires_auth_and_does_not_need_a_session() {
    let h = Harness::new(1, vec![]).await;
    let client = reqwest::Client::new();
    let url = format!("{}/api/v4/usage", h.url);
    assert_eq!(client.get(&url).send().await.unwrap().status(), 401);
    let token = h.pair().await;
    let response = client.get(&url).bearer_auth(&token).send().await.unwrap();
    assert_eq!(response.status(), 200);
    let usage: Value = response.json().await.unwrap();
    assert_eq!(usage["accounts"][0]["limits"][0]["usedFraction"], 0.62);
    assert!(usage["accounts"][0]["limits"][0].get("shared").is_none());
    assert_eq!(usage["accounts"][1]["status"], "unavailable");
    let encoded = usage.to_string();
    assert!(!encoded.contains("private@example.com"));
    assert!(!encoded.contains("private-id"));
    let second: Value = client
        .get(&url)
        .bearer_auth(&token)
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    assert_eq!(usage, second);
}
